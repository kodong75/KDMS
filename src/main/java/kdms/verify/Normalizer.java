package kdms.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import kdms.catalog.SourceDataScanner;
import kdms.ddl.Names;
import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.ValueRule;

/**
 * 검증용 값 정규화 식(KIS:docs/normalization.md §1 을 그대로 옮긴 것). 컬럼 하나를 원천 T-SQL 식과 대상 PG 식으로 바꾼다.
 * 두 식은 같은 값이면 같은 문자열(NULL 이면 NULL)을 낸다. 원천 쪽에는 적재 때 적용한 값 규칙(NUL·끝 공백·대소문자·센티널·반올림)을
 * {@link kdms.load.CopyValues} 와 같은 순서로 적용한다(규칙이 값을 바꾸면 검증도 원천을 같은 규칙으로, KIS normalization §0).
 * 대상 쪽에는 규칙을 적용하지 않는다(이미 바뀐 값이 들어 있다).
 */
public final class Normalizer {

    /** 원천 UTF-8 바이트를 만드는 콜레이션(SQL Server 2019+) */
    static final String UTF8 = "Latin1_General_100_BIN2_UTF8";
    /**
     * NUL 을 찾고 바꾸는 이진 콜레이션(일반 콜레이션은 NUL 을 무시한다). 행 해시의 UTF-8 콜레이션과 같아야 한다:
     * 코드페이지 1252 인 Latin1_General_100_BIN2 로 바꾸면 바깥 COLLATE … UTF8 를 붙여도 varchar 변환에서 한글이 '?' 가 된다(2026-10-06 실측).
     */
    static final String BIN = UTF8;

    /** 원천·대상 식. 해시에서 빠지는 컬럼이면 note 에 이유 */
    public record Expr(ColumnPlan column, String source, String target, String note) {
    }

    private Normalizer() {
    }

    public static Expr column(ColumnPlan c, List<String> sentinelDates, String nulReplacement) {
        String ms = SourceDataScanner.bracket(c.srcName());
        String pg = Names.quote(c.tgtName());
        String type = c.source().typeName();
        ValueRule v = c.value();
        String round = v == null ? null : v.round();
        boolean targetBool = c.tgtType().toLowerCase(Locale.ROOT).startsWith("bool");
        String note = null;
        String s;
        String t;
        switch (type) {
            case "char", "nchar" -> {
                // A10: char 채움 공백은 두 DB 모두 비교에서 무시 → RTRIM
                s = "RTRIM(" + text(ms, v, nulReplacement, true) + ")";
                t = "rtrim(" + pg + "::text)";
            }
            case "varchar", "nvarchar", "sysname", "text", "ntext" -> {
                s = text(ms, v, nulReplacement, false);
                t = pg + "::text";
            }
            case "bigint", "int", "smallint", "tinyint" -> {
                s = "CONVERT(varchar(20), " + ms + ")";
                t = pg + "::text";
            }
            case "bit" -> {
                s = "CASE " + ms + " WHEN 1 THEN '1' WHEN 0 THEN '0' END";
                t = targetBool ? "CASE WHEN " + pg + " THEN '1' WHEN NOT " + pg + " THEN '0' END" : pg + "::text";
            }
            case "decimal", "numeric" -> {
                s = "CONVERT(varchar(50), " + ms + ")";
                t = pg + "::text";
            }
            case "money" -> {
                // CONVERT(varchar, money) 는 소수 2자리로 반올림 → decimal 경유(A04)
                s = "CONVERT(varchar(50), CAST(" + ms + " AS decimal(19,4)))";
                t = pg + "::text";
            }
            case "smallmoney" -> {
                s = "CONVERT(varchar(50), CAST(" + ms + " AS decimal(10,4)))";
                t = pg + "::text";
            }
            case "float" -> {
                s = "CONVERT(varchar(60), CAST(" + ms + " AS decimal(38,10)))";
                t = "round(" + pg + "::numeric, 10)::text";
            }
            case "real" -> {
                // real::numeric 은 유효 6자리로 잘린다 → double 경유
                s = "CONVERT(varchar(60), CAST(" + ms + " AS decimal(38,10)))";
                t = "round(" + pg + "::float8::numeric, 10)::text";
            }
            case "date" -> {
                s = "CONVERT(char(10), " + ms + ", 23)";
                t = "to_char(" + pg + ", 'YYYY-MM-DD')";
            }
            case "datetime" -> {
                // A05: 3.33ms 값(.997 등)을 문자열로 비교. DATEPART(ms) 금지
                s = "CONVERT(char(23), " + ms + ", 121)";
                t = "to_char(" + pg + ", 'YYYY-MM-DD HH24:MI:SS.MS')";
            }
            case "smalldatetime" -> {
                s = "CONVERT(char(19), " + ms + ", 120)";
                t = "to_char(" + pg + ", 'YYYY-MM-DD HH24:MI:SS')";
            }
            case "datetime2" -> {
                // A06: 7자리 → 6자리(적재와 같은 반올림)
                s = "CONVERT(char(26), CAST(" + micro(ms, round) + " AS datetime2(6)), 121)";
                t = "to_char(" + pg + ", 'YYYY-MM-DD HH24:MI:SS.US')";
            }
            case "datetimeoffset" -> {
                // 같은 순간인지(UTC) 비교
                s = "CONVERT(char(26), CAST(SWITCHOFFSET(" + micro(ms, round) + ", '+00:00') AS datetime2(6)), 121)";
                t = "to_char(" + pg + " AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US')";
            }
            case "time" -> {
                s = "CAST(CAST(" + micro(ms, round) + " AS time(6)) AS char(15))";
                t = "to_char('2000-01-01'::date + " + pg + ", 'HH24:MI:SS.US')";
            }
            case "uniqueidentifier" -> {
                s = "LOWER(CONVERT(char(36), " + ms + "))";
                t = pg + "::text";
            }
            case "binary", "varbinary", "image", "rowversion" -> {
                s = "CONVERT(varchar(max), CAST(" + ms + " AS varbinary(max)), 2)";
                t = "upper(encode(" + pg + ", 'hex'))";
            }
            case "xml" -> {
                s = "CAST(" + ms + " AS nvarchar(max))";
                t = pg + "::text";
                note = "xml 은 두 DB 의 직렬화 표현(공백·속성 따옴표)이 달라 해시가 다를 수 있다";
            }
            default -> throw new IllegalStateException("검증 정규화 규칙이 없는 원천 타입: " + type);
        }
        if (c.source().isDateTime() && v != null && v.sentinel() != null && !"keep".equals(v.sentinel()) && !sentinelDates.isEmpty()) {
            s = sentinel(ms, s, v.sentinel(), sentinelDates);
            if ("infinity".equals(v.sentinel())) {
                t = "CASE WHEN " + pg + " = 'infinity' THEN 'infinity' WHEN " + pg + " = '-infinity' THEN '-infinity' ELSE " + t + " END";
            }
        }
        return new Expr(c, s, t, note);
    }

    /** 문자 값 규칙(적재와 같은 순서): NUL → 끝 공백 → 대소문자 */
    static String text(String ms, ValueRule v, String nulReplacement, boolean isChar) {
        String s = "CAST(" + ms + " AS nvarchar(max))";
        String nul = v == null ? null : v.nulChar();
        if ("strip".equals(nul) || "replace".equals(nul)) {
            String with = "strip".equals(nul) ? "N''" : "N'" + nulReplacement.replace("'", "''") + "'";
            s = "REPLACE(" + s + " COLLATE " + BIN + ", NCHAR(0), " + with + ")";
        }
        if (!isChar && v != null && "rtrim".equals(v.trailingSpace())) {
            s = "RTRIM(" + s + ")";
        }
        if (v != null && "upper".equals(v.caseRule())) {
            s = "UPPER(" + s + ")";
        } else if (v != null && "lower".equals(v.caseRule())) {
            s = "LOWER(" + s + ")";
        }
        return s;
    }

    /** truncate 면 마이크로초 아래를 버린 값, 아니면 그대로(CAST … (6) 이 반올림한다) */
    static String micro(String ms, String round) {
        return "truncate".equals(round) ? "DATEADD(NANOSECOND, -(DATEPART(NANOSECOND, " + ms + ") % 1000), " + ms + ")" : ms;
    }

    /** sentinel_dates.action: null | infinity 를 원천 쪽에 적용(적재 CopyValues 와 같은 기준: 1970 이전 = -infinity) */
    static String sentinel(String ms, String expr, String action, List<String> dates) {
        List<LocalDate> ds = dates.stream().map(LocalDate::parse).toList();
        String day = "CAST(" + ms + " AS date)";
        if ("null".equals(action)) {
            return "CASE WHEN " + day + " IN (" + list(ds) + ") THEN NULL ELSE " + expr + " END";
        }
        List<LocalDate> low = ds.stream().filter(d -> d.isBefore(LocalDate.of(1970, 1, 1))).toList();
        List<LocalDate> high = ds.stream().filter(d -> !d.isBefore(LocalDate.of(1970, 1, 1))).toList();
        StringBuilder b = new StringBuilder("CASE");
        if (!low.isEmpty()) {
            b.append(" WHEN ").append(day).append(" IN (").append(list(low)).append(") THEN '-infinity'");
        }
        if (!high.isEmpty()) {
            b.append(" WHEN ").append(day).append(" IN (").append(list(high)).append(") THEN 'infinity'");
        }
        return b.append(" ELSE ").append(expr).append(" END").toString();
    }

    private static String list(List<LocalDate> ds) {
        return ds.stream().map(d -> "'" + d + "'").collect(Collectors.joining(", "));
    }

    /** 행 정규화 문자열: NULL → \N, 구분자 |. ISNULL 대신 COALESCE(KIS D01, T-L17) */
    public static String sourceRow(List<Expr> exprs) {
        return exprs.stream().map(e -> "COALESCE(" + e.source() + ", N'\\N')").collect(Collectors.joining(" + N'|' + "));
    }

    public static String targetRow(List<Expr> exprs) {
        return exprs.stream().map(e -> "coalesce(" + e.target() + ", '\\N')").collect(Collectors.joining(" || '|' || "));
    }

    /** 행 해시: UTF-8 바이트 MD5 앞 8바이트 → 부호 있는 bigint (합은 decimal(38,0)) */
    public static String sourceHash(String row) {
        return "CAST(CAST(SUBSTRING(HASHBYTES('MD5', CAST((" + row + ") COLLATE " + UTF8 + " AS varchar(max))), 1, 8) AS bigint) AS decimal(38,0))";
    }

    public static String targetHash(String row) {
        return "('x' || substr(md5(" + row + "), 1, 16))::bit(64)::bigint::numeric";
    }

    /** 행 차이 찾기용 묶음 번호 0~65535 (PK 정규화 문자열의 MD5 앞 2바이트). 두 DB 정렬 순서와 관계없다 */
    public static String sourceBucket(String pk) {
        return "CAST(SUBSTRING(HASHBYTES('MD5', CAST((" + pk + ") COLLATE " + UTF8 + " AS varchar(max))), 1, 2) AS int)";
    }

    public static String targetBucket(String pk) {
        return "('x' || substr(md5(" + pk + "), 1, 4))::bit(16)::int";
    }

    /** 합계를 내는 컬럼(정수·decimal·money). float 는 해시에만(normalization §2) */
    public static boolean summed(ColumnPlan c) {
        return switch (c.source().typeName()) {
            case "bigint", "int", "smallint", "tinyint", "decimal", "numeric", "money", "smallmoney" -> true;
            default -> false;
        };
    }

    /** SUM(money) 는 money 로 누적돼 넘친다 → decimal(38, 스케일)(A04) */
    public static String sourceSum(ColumnPlan c) {
        int scale = switch (c.source().typeName()) {
            case "money", "smallmoney" -> 4;
            case "decimal", "numeric" -> c.source().scale();
            default -> 0;
        };
        return "SUM(CAST(" + SourceDataScanner.bracket(c.srcName()) + " AS decimal(38," + scale + ")))";
    }

    public static String targetSum(ColumnPlan c) {
        return "sum(" + Names.quote(c.tgtName()) + ")::numeric";
    }
}
