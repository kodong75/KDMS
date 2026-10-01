package kdms.catalog;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code kdms plan --scan}: 원천 데이터를 테이블마다 한 번 훑어 NUL 문자(A02)·센티널 날짜(A08) 행 수를 센다.
 * 행 값은 가져오지 않고 건수만 가져온다. 큰 테이블은 시간이 걸리므로 기본은 하지 않는다.
 */
public final class SourceDataScanner {

    /** @param columns 컬럼 이름(원천 그대로) → 검사 결과. 검사 대상 컬럼만 들어 있다 */
    public record TableScan(long rows, Map<String, ColumnScan> columns) {
    }

    /** nulRows: NUL 문자가 든 행 수(문자 컬럼만), sentinelRows: 센티널 날짜 행 수(날짜 컬럼만) */
    public record ColumnScan(long nulRows, long sentinelRows) {
    }

    private SourceDataScanner() {
    }

    /** @return "schema.table"(소문자) → 결과 */
    public static Map<String, TableScan> scan(Connection c, List<SourceCatalog.Table> tables, List<String> sentinelDates)
            throws SQLException {
        Map<String, TableScan> out = new LinkedHashMap<>();
        try (Statement st = c.createStatement()) {
            for (SourceCatalog.Table t : tables) {
                String sql = scanSql(t, sentinelDates);
                List<SourceCatalog.Column> cols = scanned(t);
                try (ResultSet rs = st.executeQuery(sql)) {
                    rs.next();
                    Map<String, ColumnScan> cs = new LinkedHashMap<>();
                    int i = 2;
                    for (SourceCatalog.Column col : cols) {
                        long v = rs.getLong(i++);
                        cs.put(col.name(), col.isText() ? new ColumnScan(v, 0) : new ColumnScan(0, v));
                    }
                    out.put((t.schema() + "." + t.name()).toLowerCase(Locale.ROOT), new TableScan(rs.getLong(1), cs));
                }
            }
        }
        return out;
    }

    static List<SourceCatalog.Column> scanned(SourceCatalog.Table t) {
        return t.columns().stream().filter(col -> col.computed() == null && (col.isText() || col.isDateTime())).toList();
    }

    static String scanSql(SourceCatalog.Table t, List<String> sentinelDates) {
        // 날짜 문자열은 규칙 파일에서 오므로 날짜로 해석해 다시 쓴다(SQL 에 그대로 넣지 않는다)
        String dates = sentinelDates.stream().map(d -> "'" + LocalDate.parse(d) + "'").collect(Collectors.joining(", "));
        StringBuilder b = new StringBuilder("SELECT COUNT_BIG(*)");
        for (SourceCatalog.Column col : scanned(t)) {
            String q = bracket(col.name());
            if (col.isText()) {
                // 이진 콜레이션이어야 CHARINDEX 가 NUL 을 찾는다
                b.append(",\n       COALESCE(SUM(CASE WHEN CHARINDEX(NCHAR(0) COLLATE Latin1_General_BIN2, CAST(")
                        .append(q).append(" AS nvarchar(max))) > 0 THEN 1 ELSE 0 END), 0)");
            } else if (dates.isEmpty()) {
                b.append(",\n       0");
            } else {
                b.append(",\n       COALESCE(SUM(CASE WHEN CAST(").append(q).append(" AS date) IN (").append(dates)
                        .append(") THEN 1 ELSE 0 END), 0)");
            }
        }
        b.append("\nFROM ").append(bracket(t.schema())).append('.').append(bracket(t.name()));
        return b.toString();
    }

    /** T-SQL 식별자 따옴표 */
    public static String bracket(String name) {
        return "[" + name.replace("]", "]]") + "]";
    }
}
