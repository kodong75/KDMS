package kdms.load;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import kdms.catalog.SourceCatalog;
import kdms.catalog.SourceDataScanner;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.TablePlan;

/**
 * 전체 적재 구간(plan.md §4.3 ③). 경계값은 원천 PK 컬럼 순서의 문자열 목록(kdms.load_chunk.lower_bound/upper_bound 의 JSON 배열).
 * 하한 포함·상한 미포함, null = 끝 없음. 구간 조건은 원천 MS-SQL 에서만 쓴다(대상 PG 는 콜레이션이 달라 순서가 다르다).
 * <ul>
 *   <li>정수 PK 하나: MIN~MAX 균등 분할</li>
 *   <li>그 밖의 PK: NTILE 로 경계값을 미리 뽑는다(원천 콜레이션 순서 그대로)</li>
 *   <li>PK 없음, 구간을 나눌 수 없는 PK 타입(float·time·binary 등), 구간 수 1: 테이블 전체가 구간 하나</li>
 * </ul>
 */
public final class Chunks {

    /** 구간 하나. bounds 는 PK 컬럼 수만큼의 문자열, 끝이 없으면 null */
    public record Chunk(int no, List<String> lower, List<String> upper) {
    }

    private Chunks() {
    }

    /** 원천 PK 컬럼(계획의 PK 는 대상 이름이라 원천 컬럼으로 되돌린다). PK 없으면 빈 목록 */
    public static List<ColumnPlan> keyColumns(TablePlan t) {
        if (t.primaryKey() == null) {
            return List.of();
        }
        return t.primaryKey().columns().stream()
                .map(name -> t.columns().stream().filter(c -> c.tgtName().equals(name)).findFirst().orElseThrow())
                .toList();
    }

    /** 이 타입의 PK 로 구간을 나눌 수 있나 */
    static boolean splittable(SourceCatalog.Column c) {
        return switch (c.typeName()) {
            case "bigint", "int", "smallint", "tinyint", "decimal", "numeric",
                 "char", "varchar", "nchar", "nvarchar", "sysname",
                 "date", "datetime", "datetime2", "smalldatetime", "uniqueidentifier" -> true;
            default -> false;
        };
    }

    /** 원천에서 경계값을 읽어 구간을 만든다. 읽기만 한다 */
    public static List<Chunk> plan(Connection src, TablePlan t, int wanted) throws SQLException {
        List<ColumnPlan> keys = keyColumns(t);
        if (wanted <= 1 || keys.isEmpty() || !keys.stream().allMatch(k -> splittable(k.source()))) {
            return List.of(new Chunk(1, null, null));
        }
        List<List<String>> starts = new ArrayList<>();
        try (Statement st = src.createStatement()) {
            if (keys.size() == 1 && CopyValues.integer(keys.get(0).source())) {
                String k = SourceDataScanner.bracket(keys.get(0).srcName());
                try (ResultSet rs = st.executeQuery("SELECT CONVERT(varchar(20), MIN(" + k + ")), CONVERT(varchar(20), MAX(" + k + ")) FROM "
                        + from(t))) {
                    rs.next();
                    if (rs.getString(1) != null) {
                        evenSplit(new BigInteger(rs.getString(1)), new BigInteger(rs.getString(2)), wanted)
                                .forEach(b -> starts.add(List.of(b.toString())));
                    }
                }
            } else {
                try (ResultSet rs = st.executeQuery(ntileSql(t, keys, wanted))) {
                    while (rs.next()) {
                        List<String> b = new ArrayList<>();
                        for (int i = 1; i <= keys.size(); i++) {
                            b.add(rs.getString(i));
                        }
                        starts.add(b);
                    }
                }
            }
        }
        return fromStarts(starts);
    }

    /** 경계값(둘째 구간부터의 시작값) → 구간 목록 */
    static List<Chunk> fromStarts(List<List<String>> starts) {
        List<Chunk> out = new ArrayList<>();
        List<String> lower = null;
        int no = 1;
        for (List<String> s : starts) {
            out.add(new Chunk(no++, lower, s));
            lower = s;
        }
        out.add(new Chunk(no, lower, null));
        return out;
    }

    /** [min, max] 를 n 개로 나눈 둘째 구간부터의 시작값(겹치지 않게, 값이 적으면 구간도 적다) */
    static List<BigInteger> evenSplit(BigInteger min, BigInteger max, int n) {
        BigInteger span = max.subtract(min).add(BigInteger.ONE);
        List<BigInteger> out = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            BigInteger b = min.add(span.multiply(BigInteger.valueOf(i)).divide(BigInteger.valueOf(n)));
            if (b.compareTo(min) > 0 && b.compareTo(max) <= 0 && (out.isEmpty() || b.compareTo(out.get(out.size() - 1)) > 0)) {
                out.add(b);
            }
        }
        return out;
    }

    static String ntileSql(TablePlan t, List<ColumnPlan> keys, int n) {
        String cols = keys.stream().map(k -> SourceDataScanner.bracket(k.srcName())).collect(Collectors.joining(", "));
        String conv = keys.stream().map(k -> boundExpr(k.source(), SourceDataScanner.bracket(k.srcName()))).collect(Collectors.joining(", "));
        return "SELECT " + conv + "\nFROM (SELECT " + cols + ", nt, ROW_NUMBER() OVER (PARTITION BY nt ORDER BY " + cols + ") AS rn\n"
                + "      FROM (SELECT " + cols + ", NTILE(" + n + ") OVER (ORDER BY " + cols + ") AS nt FROM " + from(t) + ") a) b\n"
                + "WHERE rn = 1 AND nt > 1\nORDER BY nt";
    }

    /** 경계값을 문자열로 읽는 식. 다시 같은 값으로 되돌릴 수 있는 형식(스타일 121 등) */
    static String boundExpr(SourceCatalog.Column c, String q) {
        return switch (c.typeName()) {
            case "date" -> "CONVERT(char(10), " + q + ", 23)";
            case "datetime", "datetime2" -> "CONVERT(varchar(27), " + q + ", 121)";
            case "smalldatetime" -> "CONVERT(char(19), " + q + ", 120)";
            case "uniqueidentifier" -> "CONVERT(char(36), " + q + ")";
            case "char", "varchar", "nchar", "nvarchar", "sysname" -> q;
            default -> "CONVERT(varchar(50), " + q + ")";
        };
    }

    /** 경계값 문자열 매개변수를 원천 타입으로 되돌리는 식 */
    static String paramExpr(SourceCatalog.Column c) {
        return switch (c.typeName()) {
            case "date" -> "CONVERT(date, ?, 23)";
            case "datetime" -> "CONVERT(datetime, ?, 121)";
            case "datetime2" -> "CONVERT(datetime2(" + c.scale() + "), ?, 121)";
            case "smalldatetime" -> "CONVERT(smalldatetime, ?, 120)";
            case "uniqueidentifier" -> "CONVERT(uniqueidentifier, ?)";
            case "decimal", "numeric" -> "CAST(? AS " + c.typeName() + "(" + c.precision() + "," + c.scale() + "))";
            case "bigint", "int", "smallint", "tinyint" -> "CAST(? AS " + c.typeName() + ")";
            default -> "?"; // 문자: 비교는 원천 컬럼 콜레이션으로(NTILE 순서와 같다)
        };
    }

    /**
     * 구간 조건(원천 T-SQL, 행 값 비교를 펼친 것). 하한: (k1 &gt; a1) OR (k1 = a1 AND k2 &gt;= a2) …
     *
     * @param params 매개변수 값이 순서대로 들어간다
     */
    static String where(List<ColumnPlan> keys, Chunk chunk, List<String> params) {
        List<String> parts = new ArrayList<>();
        if (chunk.lower() != null) {
            parts.add(compare(keys, chunk.lower(), ">", ">=", params));
        }
        if (chunk.upper() != null) {
            parts.add(compare(keys, chunk.upper(), "<", "<", params));
        }
        return parts.isEmpty() ? "" : "\nWHERE " + String.join("\n  AND ", parts);
    }

    private static String compare(List<ColumnPlan> keys, List<String> bound, String strict, String last, List<String> params) {
        List<String> ors = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            List<String> ands = new ArrayList<>();
            for (int j = 0; j < i; j++) {
                ands.add(SourceDataScanner.bracket(keys.get(j).srcName()) + " = " + paramExpr(keys.get(j).source()));
                params.add(bound.get(j));
            }
            String op = i == keys.size() - 1 ? last : strict;
            ands.add(SourceDataScanner.bracket(keys.get(i).srcName()) + " " + op + " " + paramExpr(keys.get(i).source()));
            params.add(bound.get(i));
            ors.add(ands.size() == 1 ? ands.get(0) : "(" + String.join(" AND ", ands) + ")");
        }
        return ors.size() == 1 ? ors.get(0) : "(" + String.join(" OR ", ors) + ")";
    }

    /** 구간 하나를 읽는 원천 SELECT. 매개변수는 params 에 */
    public static String selectSql(TablePlan t, List<ColumnPlan> columns, Chunk chunk, List<String> params) {
        String cols = columns.stream().map(c -> SourceDataScanner.bracket(c.srcName())).collect(Collectors.joining(", "));
        return "SELECT " + cols + "\nFROM " + from(t) + where(keyColumns(t), chunk, params);
    }

    static String from(TablePlan t) {
        return SourceDataScanner.bracket(t.srcSchema()) + "." + SourceDataScanner.bracket(t.srcName());
    }

    /** 구간 행 수 세기(시험·진단용) */
    static long count(Connection src, TablePlan t, Chunk chunk) throws SQLException {
        List<String> params = new ArrayList<>();
        String sql = "SELECT COUNT_BIG(*) FROM " + from(t) + where(keyColumns(t), chunk, params);
        try (PreparedStatement ps = src.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
