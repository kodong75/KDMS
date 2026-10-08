package kdms.load;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import kdms.catalog.SourceCatalog;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.ValueRule;

/**
 * 원천 JDBC 값 → PG {@code COPY … (FORMAT text)} 한 칸. 값 규칙(plan.md §5: NUL·끝 공백·대소문자·센티널·반올림)을 여기서 적용한다.
 * 검증({@code kdms.verify.Normalizer})은 같은 규칙을 원천 SQL 로 적용하므로 둘을 함께 고친다(docs/load-verify.md §3).
 * <p>
 * 4단계 CDC 반영기도 Debezium 값을 같은 정규 값(문자열·BigDecimal·LocalDateTime…)으로 바꾼 뒤 이 변환을 지나게 한다(plan.md §5 끝).
 */
public final class CopyValues {

    /** COPY text 의 NULL */
    public static final String NULL = "\\N";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter TSTZ = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSxxx");

    /** 센티널 날짜 중 이 날짜보다 앞이면 -infinity, 아니면 infinity (sentinel_dates.action: infinity) */
    static final LocalDate INFINITY_SPLIT = LocalDate.of(1970, 1, 1);

    /** 컬럼 하나의 변환기. 원천 SELECT 의 열 번호 순서대로 만든다 */
    public static final class Column {
        final ColumnPlan plan;
        final String type;
        final ValueRule rule;
        final boolean targetBoolean;
        final String nulReplacement;
        final Set<LocalDate> sentinels;
        /** NUL 문자가 든 행 수(nul_char: fail 일 때 세기만 하고 적재는 멈춘다) */
        long nulRows;

        Column(ColumnPlan plan, String nulReplacement, Set<LocalDate> sentinels) {
            this.plan = plan;
            this.type = plan.source().typeName();
            this.rule = plan.value();
            this.targetBoolean = plan.tgtType().toLowerCase(Locale.ROOT).startsWith("bool");
            this.nulReplacement = nulReplacement;
            this.sentinels = sentinels;
        }

        public ColumnPlan plan() {
            return plan;
        }

        public long nulRows() {
            return nulRows;
        }
    }

    private CopyValues() {
    }

    public static List<Column> columns(List<ColumnPlan> plans, String nulReplacement, List<String> sentinelDates) {
        Set<LocalDate> s = sentinelDates.stream().map(LocalDate::parse).collect(Collectors.toUnmodifiableSet());
        return plans.stream().map(p -> new Column(p, nulReplacement, s)).toList();
    }

    /** 이 원천 타입을 적재할 수 있나(그 밖의 타입은 kdms plan 이 규칙 없음 오류로 막는다) */
    public static boolean supported(String sourceType) {
        return switch (sourceType) {
            case "bigint", "int", "smallint", "tinyint", "bit", "decimal", "numeric", "money", "smallmoney", "float", "real",
                 "date", "time", "datetime", "smalldatetime", "datetime2", "datetimeoffset",
                 "char", "varchar", "nchar", "nvarchar", "sysname", "text", "ntext",
                 "binary", "varbinary", "image", "rowversion", "uniqueidentifier", "xml" -> true;
            default -> false;
        };
    }

    /**
     * ResultSet 의 i 번째 값을 COPY text 칸으로(이스케이프 포함) 바꿔 b 에 붙인다.
     *
     * @return false 면 nul_char: fail 인데 NUL 이 있었다(적재를 멈춘다)
     */
    public static boolean append(StringBuilder b, ResultSet rs, int i, Column c) throws SQLException {
        long nulBefore = c.nulRows;
        String v = read(rs, i, c);
        if (c.nulRows != nulBefore) {
            return false;
        }
        if (v == null) {
            b.append(NULL);
        } else {
            escape(b, v);
        }
        return true;
    }

    /** 원천 값 → 대상 입력 문자열(이스케이프 전). NULL 이면 null */
    static String read(ResultSet rs, int i, Column c) throws SQLException {
        return value(c, fetch(rs, i, c.type));
    }

    /**
     * 원천 JDBC 값을 정규 값으로 읽는다. 4단계 CDC 는 Debezium 값을 같은 정규 값으로 바꿔({@code kdms.cdc.CdcValues}) {@link #value} 를 지난다.
     * <p>정규 값: 문자·uniqueidentifier·xml = String, 정수 = Long, bit = Boolean, decimal·money = BigDecimal, float = Double, real = Float,
     * date = LocalDate, datetime·datetime2·smalldatetime = LocalDateTime, datetimeoffset = OffsetDateTime, time = LocalTime, 바이너리 = byte[]
     */
    static Object fetch(ResultSet rs, int i, String type) throws SQLException {
        return switch (type) {
            case "char", "varchar", "nchar", "nvarchar", "sysname", "text", "ntext", "uniqueidentifier", "xml" -> rs.getString(i);
            case "bigint", "int", "smallint", "tinyint" -> {
                long v = rs.getLong(i);
                yield rs.wasNull() ? null : v;
            }
            case "bit" -> {
                boolean v = rs.getBoolean(i);
                yield rs.wasNull() ? null : v;
            }
            case "decimal", "numeric", "money", "smallmoney" -> rs.getBigDecimal(i);
            case "float" -> {
                double v = rs.getDouble(i);
                yield rs.wasNull() ? null : v;
            }
            case "real" -> {
                float v = rs.getFloat(i);
                yield rs.wasNull() ? null : v;
            }
            case "date" -> rs.getObject(i, LocalDate.class);
            case "datetime", "smalldatetime", "datetime2" -> rs.getObject(i, LocalDateTime.class);
            case "datetimeoffset" -> rs.getObject(i, OffsetDateTime.class);
            case "time" -> rs.getObject(i, LocalTime.class);
            case "binary", "varbinary", "image", "rowversion" -> rs.getBytes(i);
            default -> throw new IllegalStateException("적재할 수 없는 원천 타입: " + type);
        };
    }

    /**
     * 정규 값({@link #fetch}) → 대상 입력 문자열(이스케이프 전). 값 규칙을 여기서 적용한다. NULL 이면 null.
     * nul_char: fail 인데 NUL 이 있으면 {@link Column#nulRows()} 를 늘리고 값은 그대로 돌려준다(부른 쪽이 멈춘다).
     */
    public static String value(Column c, Object v) {
        if (v == null) {
            return null;
        }
        switch (c.type) {
            case "char", "varchar", "nchar", "nvarchar", "sysname", "text", "ntext" -> {
                return text((String) v, c);
            }
            case "bigint", "int", "smallint", "tinyint" -> {
                return Long.toString(((Number) v).longValue());
            }
            case "bit" -> {
                boolean b = (Boolean) v;
                return c.targetBoolean ? (b ? "t" : "f") : (b ? "1" : "0");
            }
            case "decimal", "numeric", "money", "smallmoney" -> {
                return ((BigDecimal) v).toPlainString();
            }
            case "float" -> {
                return Double.toString(((Number) v).doubleValue());
            }
            case "real" -> {
                return Float.toString(((Number) v).floatValue());
            }
            case "date" -> {
                LocalDate d = (LocalDate) v;
                return sentinel(d, c, () -> DATE.format(d));
            }
            case "datetime", "smalldatetime", "datetime2" -> {
                LocalDateTime t = (LocalDateTime) v;
                return sentinel(t.toLocalDate(), c, () -> TS.format(round(t, c.rule.round())));
            }
            case "datetimeoffset" -> {
                OffsetDateTime t = (OffsetDateTime) v;
                return sentinel(t.toLocalDate(), c, () -> TSTZ.format(roundOffset(t, c.rule.round())));
            }
            case "time" -> {
                return time((LocalTime) v, c.rule.round());
            }
            case "uniqueidentifier" -> {
                return ((String) v).toLowerCase(Locale.ROOT); // PG uuid 는 소문자(normalization §1)
            }
            case "binary", "varbinary", "image", "rowversion" -> {
                return "\\x" + HexFormat.of().formatHex((byte[]) v);
            }
            case "xml" -> {
                return (String) v;
            }
            default -> throw new IllegalStateException("적재할 수 없는 원천 타입: " + c.type);
        }
    }

    /** 값 규칙 순서: NUL → 끝 공백 → 대소문자. 검증의 원천 정규화 식도 같은 순서다 */
    static String text(String s, Column c) {
        if (s.indexOf('\0') >= 0) {
            String rule = c.rule == null || c.rule.nulChar() == null ? "fail" : c.rule.nulChar();
            switch (rule) {
                case "strip" -> s = s.replace("\0", "");
                case "replace" -> s = s.replace("\0", c.nulReplacement);
                default -> {
                    c.nulRows++; // fail: 값은 쓰지 않고 센다
                    return s;
                }
            }
        }
        if (c.rule != null && "rtrim".equals(c.rule.trailingSpace())) {
            s = rtrim(s);
        }
        if (c.rule != null && c.rule.caseRule() != null) {
            switch (c.rule.caseRule()) {
                case "upper" -> s = s.toUpperCase(Locale.ROOT);
                case "lower" -> s = s.toLowerCase(Locale.ROOT);
                default -> {
                }
            }
        }
        return s;
    }

    /** T-SQL RTRIM·PG rtrim(x) 과 같이 공백(U+0020)만 지운다 */
    static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    private static String sentinel(LocalDate d, Column c, java.util.function.Supplier<String> value) {
        if (c.rule != null && c.rule.sentinel() != null && !"keep".equals(c.rule.sentinel()) && c.sentinels.contains(d)) {
            if ("null".equals(c.rule.sentinel())) {
                return null;
            }
            return d.isBefore(INFINITY_SPLIT) ? "-infinity" : "infinity";
        }
        return value.get();
    }

    /** 마이크로초(PG 최대 정밀도)로 맞춘다. 기본 half_up(A06), truncate 면 버림 */
    static LocalDateTime round(LocalDateTime v, String mode) {
        long sub = v.getNano() % 1000;
        if (sub == 0) {
            return v;
        }
        LocalDateTime down = v.minusNanos(sub);
        return "truncate".equals(mode) || sub < 500 ? down : down.plusNanos(1000);
    }

    static OffsetDateTime roundOffset(OffsetDateTime v, String mode) {
        LocalDateTime r = round(v.toLocalDateTime(), mode);
        return OffsetDateTime.of(r, v.getOffset());
    }

    static String time(LocalTime v, String mode) {
        long sub = v.getNano() % 1000;
        if (sub == 0) {
            return TIME.format(v);
        }
        LocalTime down = v.minusNanos(sub);
        if ("truncate".equals(mode) || sub < 500) {
            return TIME.format(down);
        }
        LocalTime up = down.plusNanos(1000);
        return up.isBefore(down) ? "24:00:00" : TIME.format(up); // 23:59:59.9999995 → 하루 끝(PG time 허용)
    }

    /** COPY text 이스케이프: 역슬래시·줄바꿈·CR·탭(T-L15, KIS:docs/issues.md D02) */
    static void escape(StringBuilder b, String v) {
        for (int k = 0; k < v.length(); k++) {
            char ch = v.charAt(k);
            switch (ch) {
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(ch);
            }
        }
    }

    /** 시험용: 값 하나를 바로 바꾼다 */
    static String escape(String v) {
        StringBuilder b = new StringBuilder();
        escape(b, v);
        return b.toString();
    }

    /** 이 원천 타입이 정수형인가(구간 분할) */
    static boolean integer(SourceCatalog.Column c) {
        return switch (c.typeName()) {
            case "bigint", "int", "smallint", "tinyint" -> true;
            default -> false;
        };
    }
}
