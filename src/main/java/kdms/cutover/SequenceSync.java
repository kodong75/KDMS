package kdms.cutover;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import kdms.ddl.Names;
import kdms.ddl.SchemaPlan;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.TablePlan;

/**
 * 전환 때 대상 IDENTITY·SEQUENCE 의 다음 값을 원천에 맞춘다(plan.md §4.5 ⑤, KIS:docs/issues.md A09, T-L05·T-L06).
 * 원천 값은 쓰기를 멈춘 뒤 이 자리에서 다시 읽는다(계획 때 읽은 값은 그 뒤 쓰기로 낡았다).
 * <ul>
 * <li>IDENTITY: 원천 IDENT_CURRENT(sys.identity_columns.last_value). 삭제·RESEED 로 MAX(id) 보다 클 수 있어 MAX 가 아니라 이 값을 쓴다.
 *     대상 MAX 가 더 앞서 있으면(IDENTITY_INSERT 로 넣은 행) 그 값을 쓰고 알린다. 한 번도 안 썼으면 다음 값 = 시작값</li>
 * <li>SEQUENCE: sys.sequences.current_value. last_used_value 가 NULL(한 번도 안 씀)이면 다음 값 = 시작값</li>
 * </ul>
 * 다시 실행해도 같은 값을 넣는다(멱등).
 */
public final class SequenceSync {

    /**
     * @param target "스키마"."테이블"."컬럼" 또는 "스키마"."시퀀스"
     * @param srcValue 원천 현재값(IDENT_CURRENT·current_value), 한 번도 안 썼으면 null
     * @param next     맞춘 뒤 대상의 다음 값
     * @param note     원천 값 대신 다른 값을 쓴 이유, 없으면 null
     */
    public record Item(String kind, String target, BigInteger srcValue, BigInteger next, String note) {
    }

    private SequenceSync() {
    }

    /**
     * 전환 뒤 점검(kdms check): 대상 시퀀스의 지금 다음 값이 원천 기준 기대값과 같은지. 시퀀스는 바꾸지 않는다(nextval 없음).
     *
     * @param expected 원천 IDENT_CURRENT·current_value 로 고른 다음 값(setval 이 넣었어야 할 값)
     * @param actual   대상 시퀀스의 지금 다음 값
     * @param tgtMax   대상 MAX(증가값이 음수면 MIN), SEQUENCE 이거나 행이 없으면 null
     */
    public record Check(String kind, String target, BigInteger srcValue, BigInteger expected, BigInteger actual, BigInteger inc,
            BigInteger tgtMax) {
        /** 다음 값이 기대값과 같고, 대상에 이미 있는 값과 겹치지 않는다 */
        public boolean ok() {
            boolean ahead = tgtMax == null || (inc.signum() >= 0 ? actual.compareTo(tgtMax) > 0 : actual.compareTo(tgtMax) < 0);
            return expected.equals(actual) && ahead;
        }
    }

    /** setval 할 대상 하나(원천에서 읽은 값으로 고른 값) */
    private record Target(String kind, String where, String seq, BigInteger srcValue, Value value, BigInteger inc, BigInteger tgtMax) {
    }

    public static List<Item> run(SchemaPlan plan, Connection src, Connection tgt) throws SQLException {
        List<Item> out = new ArrayList<>();
        for (Target t : targets(plan, src, tgt)) {
            setval(tgt, t.seq(), t.value());
            out.add(new Item(t.kind(), t.where(), t.srcValue(), t.value().next(t.inc()), t.value().note()));
        }
        return out;
    }

    public static List<Check> check(SchemaPlan plan, Connection src, Connection tgt) throws SQLException {
        List<Check> out = new ArrayList<>();
        for (Target t : targets(plan, src, tgt)) {
            BigInteger actual;
            // 시퀀스 relation 을 직접 읽는다(pg_sequences.last_value 는 is_called = false 면 NULL 이라 다음 값을 알 수 없다)
            try (PreparedStatement ps = tgt.prepareStatement("SELECT last_value::text, is_called FROM " + t.seq());
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                actual = new Value(big(rs.getString(1)), rs.getBoolean(2), null).next(t.inc());
            }
            out.add(new Check(t.kind(), t.where(), t.srcValue(), t.value().next(t.inc()), actual, t.inc(), t.tgtMax()));
        }
        return out;
    }

    private static List<Target> targets(SchemaPlan plan, Connection src, Connection tgt) throws SQLException {
        List<Target> out = new ArrayList<>();
        for (TablePlan t : plan.tables()) {
            for (ColumnPlan c : t.columns()) {
                if (c.identity() != null) {
                    out.add(identity(t, c, src, tgt));
                }
            }
        }
        for (SchemaPlan.SequencePlan s : plan.sequences()) {
            out.add(sequence(s, src));
        }
        return out;
    }

    static String bracket(String schema, String name) {
        return "[" + schema.replace("]", "]]") + "].[" + name.replace("]", "]]") + "]";
    }

    private static Target identity(TablePlan t, ColumnPlan c, Connection src, Connection tgt) throws SQLException {
        BigInteger last;
        BigInteger seed;
        BigInteger inc;
        try (PreparedStatement ps = src.prepareStatement("""
                SELECT CONVERT(nvarchar(40), last_value), CONVERT(nvarchar(40), seed_value), CONVERT(nvarchar(40), increment_value)
                FROM sys.identity_columns WHERE object_id = OBJECT_ID(?) AND name = ?""")) {
            ps.setString(1, bracket(t.srcSchema(), t.srcName()));
            ps.setString(2, c.srcName());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("원천 IDENTITY 를 찾지 못했다: " + t.srcQualified() + "." + c.srcName());
                }
                last = big(rs.getString(1));
                seed = big(rs.getString(2));
                inc = big(rs.getString(3));
            }
        }
        String col = Names.quote(c.tgtName());
        BigInteger tgtMax;
        try (PreparedStatement ps = tgt.prepareStatement("SELECT " + (inc.signum() < 0 ? "min" : "max") + "(" + col + ")::text FROM " + t.tgtQualified())) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                tgtMax = big(rs.getString(1));
            }
        }
        String seq;
        try (PreparedStatement ps = tgt.prepareStatement("SELECT pg_get_serial_sequence(?, ?)")) {
            ps.setString(1, t.tgtQualified());
            ps.setString(2, c.tgtName());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                seq = rs.getString(1);
            }
        }
        if (seq == null) {
            throw new SQLException("대상 " + t.tgtQualified() + "." + col + " 에 IDENTITY 시퀀스가 없다");
        }
        String where = t.tgtQualified() + "." + col;
        return new Target("IDENTITY", where, seq, last, choose(last, seed, inc, tgtMax), inc, tgtMax);
    }

    private static Target sequence(SchemaPlan.SequencePlan s, Connection src) throws SQLException {
        BigInteger current;
        BigInteger lastUsed;
        BigInteger start;
        BigInteger inc;
        try (PreparedStatement ps = src.prepareStatement("""
                SELECT CONVERT(nvarchar(40), current_value), CONVERT(nvarchar(40), last_used_value),
                       CONVERT(nvarchar(40), start_value), CONVERT(nvarchar(40), increment)
                FROM sys.sequences WHERE object_id = OBJECT_ID(?)""")) {
            ps.setString(1, bracket(s.source().schema(), s.source().name()));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("원천 SEQUENCE 를 찾지 못했다: " + s.source().schema() + "." + s.source().name());
                }
                current = big(rs.getString(1));
                lastUsed = big(rs.getString(2));
                start = big(rs.getString(3));
                inc = big(rs.getString(4));
            }
        }
        String seq = Names.quote(s.tgtSchema()) + "." + Names.quote(s.tgtName());
        Value v = lastUsed == null ? new Value(start, false, null) : new Value(current, true, null);
        return new Target("SEQUENCE", seq, seq, lastUsed == null ? null : current, v, inc, null);
    }

    /** setval 에 넣을 값. called = true 면 다음 값은 value + 증가값, false 면 value */
    record Value(BigInteger value, boolean called, String note) {
        BigInteger next(BigInteger inc) {
            return called ? value.add(inc) : value;
        }
    }

    /**
     * @param last   원천 IDENT_CURRENT, 한 번도 안 썼으면 null
     * @param tgtMax 대상 MAX(증가값이 음수면 MIN), 행이 없으면 null
     */
    static Value choose(BigInteger last, BigInteger seed, BigInteger inc, BigInteger tgtMax) {
        boolean up = inc.signum() >= 0;
        BigInteger base = last;
        if (tgtMax != null && (base == null ? (up ? tgtMax.compareTo(seed) >= 0 : tgtMax.compareTo(seed) <= 0)
                : (up ? tgtMax.compareTo(base) > 0 : tgtMax.compareTo(base) < 0))) {
            return new Value(tgtMax, true, "대상 " + (up ? "MAX " : "MIN ") + tgtMax + " 이 원천 IDENT_CURRENT "
                    + (last == null ? "없음" : last) + " 보다 앞서 있어 대상 값을 썼다(IDENTITY_INSERT 로 넣은 행)");
        }
        return base == null ? new Value(seed, false, null) : new Value(base, true, null);
    }

    private static void setval(Connection tgt, String seq, Value v) throws SQLException {
        try (PreparedStatement ps = tgt.prepareStatement("SELECT setval(?::regclass, ?::bigint, ?)")) {
            ps.setString(1, seq);
            ps.setString(2, v.value().toString());
            ps.setBoolean(3, v.called());
            ps.execute();
        }
    }

    private static BigInteger big(String s) {
        return s == null ? null : new java.math.BigDecimal(s.trim()).toBigIntegerExact();
    }
}
