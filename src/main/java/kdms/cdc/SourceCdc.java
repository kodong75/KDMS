package kdms.cdc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 원천 MS-SQL 의 CDC 상태 조회. 읽기만 한다. 필요한 권한: cdc 스키마 SELECT(lsn_time_mapping·change_tables),
 * VIEW DATABASE STATE(sys.dm_cdc_log_scan_sessions). test/sql/mssql/20_grant_kdms_login.sql 이 준다.
 */
public final class SourceCdc {

    /**
     * @param at       원천 서버 시각(SYSDATETIME)
     * @param maxTxLsn 캡처된 마지막 트랜잭션 LSN(Debezium 이 읽을 끝과 같은 식). 아직 없으면 null
     * @param maxTxAt  그 커밋 시각(원천 시계)
     * @param lastScanAt 캡처 Job 이 마지막으로 로그 훑기를 끝낸 시각(빈 훑기 포함). 캡처 Job 이 멈추면 더 늘지 않는다
     * @param logReuseWait 원천 DB 의 log_reuse_wait_desc(REPLICATION 이면 캡처가 따라오지 못해 로그가 쌓이는 중, plan.md R2)
     */
    public record Snapshot(LocalDateTime at, String maxTxLsn, LocalDateTime maxTxAt, LocalDateTime lastScanAt, String logReuseWait) {

        /** 캡처 Job 이 이만큼 훑지 않았으면 멈춘 것으로 본다(기본 폴링 5초의 세 배) */
        static final long CAPTURE_STALE_SECONDS = 15;

        /** 마지막 훑기 뒤 지난 초. 기록이 없으면 null */
        public Long sinceLastScanSeconds() {
            return lastScanAt == null || at == null ? null : Math.max(0, java.time.Duration.between(lastScanAt, at).toSeconds());
        }

        /** 캡처 Job 이 멈춘 것으로 보이나(SQL Agent 중지 등, T-C09) */
        public boolean captureStale() {
            Long s = sinceLastScanSeconds();
            return s != null && s > CAPTURE_STALE_SECONDS;
        }
    }

    private SourceCdc() {
    }

    /** "schema.table"(소문자) 중 CDC 캡처 인스턴스가 있는 것 */
    public static Set<String> capturedTables(Connection c) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT OBJECT_SCHEMA_NAME(source_object_id), OBJECT_NAME(source_object_id)
                     FROM cdc.change_tables""")) {
            while (rs.next()) {
                if (rs.getString(1) != null) {
                    out.add((rs.getString(1) + "." + rs.getString(2)).toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** Debezium SqlServerConnection 의 GET_MAX_TRANSACTION_LSN 과 같은 식(tran_id 0x00 = 변경 없는 표시 행) */
    public static Snapshot snapshot(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT SYSDATETIME(), m.start_lsn, m.tran_end_time,
                            (SELECT MAX(end_time) FROM sys.dm_cdc_log_scan_sessions WHERE session_id > 0),
                            (SELECT log_reuse_wait_desc FROM sys.databases WHERE database_id = DB_ID())
                     FROM (SELECT 1 AS one) AS d
                     LEFT JOIN cdc.lsn_time_mapping AS m
                       ON m.start_lsn = (SELECT MAX(start_lsn) FROM cdc.lsn_time_mapping WHERE tran_id <> 0x00)""")) {
            rs.next();
            return new Snapshot(rs.getObject(1, LocalDateTime.class), Lsn.format(rs.getBytes(2)), rs.getObject(3, LocalDateTime.class),
                    rs.getObject(4, LocalDateTime.class), rs.getString(5));
        }
    }

    /**
     * LSN 이하에서 마지막으로 커밋된 트랜잭션의 시각(원천 시계). Debezium 이 읽은 위치(하트비트 오프셋)는 트랜잭션 LSN 과 꼭 같지 않아
     * 정확히 같은 행 대신 그 위치까지 커밋된 마지막 것을 쓴다. 없으면 null
     */
    public static LocalDateTime timeAtOrBefore(Connection c, String lsn) throws SQLException {
        if (lsn == null) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT TOP (1) tran_end_time FROM cdc.lsn_time_mapping
                WHERE start_lsn <= ? AND tran_id <> 0x00 ORDER BY start_lsn DESC""")) {
            ps.setBytes(1, java.util.HexFormat.of().parseHex(lsn.replace(":", "")));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, LocalDateTime.class) : null;
            }
        }
    }

    /**
     * CDC 캡처 Job 이 since(원천 시계) 뒤에 로그를 한 번 다 훑었나. 그러면 since 전에 커밋된 변경은 모두 변경 테이블에 있다.
     * 변경이 없으면 원천 max LSN 이 움직이지 않으므로(2026-10-08 클라우드 실측) LSN 시각 대신 캡처 Job 의 훑기 기록을 본다.
     * 빈 훑기는 새 행을 만들지 않고 마지막 행의 end_time·empty_scan_count 를 갱신한다.
     */
    public static boolean scannedSince(Connection c, LocalDateTime since) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM sys.dm_cdc_log_scan_sessions
                WHERE session_id > 0 AND scan_phase = 'Done'
                  AND (start_time >= ? OR (empty_scan_count > 0 AND end_time >= ?))""")) {
            ps.setObject(1, since);
            ps.setObject(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** 원천 서버 시각 */
    public static LocalDateTime now(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT SYSDATETIME()")) {
            rs.next();
            return rs.getObject(1, LocalDateTime.class);
        }
    }
}
