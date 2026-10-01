package kdms.catalog;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import kdms.config.Jdbc;
import kdms.config.KdmsConfig;
import kdms.state.SchemaInstaller;

/**
 * 원천·대상에 접속해 버전과 이관 준비 상태를 읽는다. 읽기만 한다(아무것도 만들지 않는다).
 */
public final class DbProbe {

    private DbProbe() {
    }

    public static ProbeResult probeSource(KdmsConfig.Endpoint e) {
        long t0 = System.nanoTime();
        try (Connection c = Jdbc.openSource(e); Statement st = c.createStatement()) {
            List<ProbeResult.Item> items = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("""
                    SELECT CAST(SERVERPROPERTY('ProductVersion')     AS nvarchar(128)),
                           CAST(SERVERPROPERTY('ProductLevel')       AS nvarchar(128)),
                           CAST(SERVERPROPERTY('ProductUpdateLevel') AS nvarchar(128)),
                           CAST(SERVERPROPERTY('Edition')            AS nvarchar(128)),
                           @@VERSION,
                           DB_NAME(),
                           CAST(DATABASEPROPERTYEX(DB_NAME(), 'Collation') AS nvarchar(128)),
                           SUSER_SNAME()""")) {
                rs.next();
                items.add(new ProbeResult.Item("버전", firstLine(rs.getString(5))));
                items.add(new ProbeResult.Item("제품 버전", join(rs.getString(1), rs.getString(2), rs.getString(3)) + " / " + rs.getString(4)));
                items.add(new ProbeResult.Item("DB", rs.getString(6)));
                items.add(new ProbeResult.Item("콜레이션", rs.getString(7)));
                items.add(new ProbeResult.Item("로그인", rs.getString(8)));
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT d.is_cdc_enabled, d.snapshot_isolation_state_desc, d.recovery_model_desc,
                           (SELECT COUNT(*) FROM sys.tables t WHERE t.is_tracked_by_cdc = 1)
                    FROM sys.databases d WHERE d.database_id = DB_ID()""")) {
                rs.next();
                boolean cdc = rs.getBoolean(1);
                int tracked = rs.getInt(4);
                items.add(new ProbeResult.Item("CDC", cdc ? "켜짐 (캡처 테이블 " + tracked + "개)" : "꺼짐"));
                items.add(new ProbeResult.Item("스냅숏 격리", rs.getString(2)));
                items.add(new ProbeResult.Item("복구 모델", rs.getString(3)));
                if (!cdc) {
                    warnings.add("원천 DB 에 CDC 가 꺼져 있다(test/sql/mssql/10_enable_cdc.sql)");
                }
                if (!"ON".equals(rs.getString(2))) {
                    warnings.add("ALLOW_SNAPSHOT_ISOLATION 이 꺼져 있다(전체 적재가 일관된 시점으로 읽지 못함)");
                }
            }
            String agent;
            try (ResultSet rs = st.executeQuery(
                    "SELECT status_desc FROM sys.dm_server_services WHERE servicename LIKE N'SQL Server Agent%'")) {
                agent = rs.next() ? rs.getString(1) : "없음";
                if (!"Running".equals(agent)) {
                    warnings.add("SQL Server Agent 가 실행 중이 아니다. CDC 캡처가 멈춘다(관리자 PowerShell: Start-Service SQLSERVERAGENT)");
                }
            } catch (SQLException ex) {
                agent = "확인 불가(VIEW SERVER STATE 권한 필요)";
            }
            items.add(new ProbeResult.Item("SQL Agent", agent));
            return new ProbeResult("원천", e.toString(), true, ms(t0), List.copyOf(items), List.copyOf(warnings), null);
        } catch (SQLException ex) {
            return ProbeResult.failed("원천", e.toString(), ms(t0), ex.getMessage());
        }
    }

    public static ProbeResult probeTarget(KdmsConfig.Endpoint e) {
        long t0 = System.nanoTime();
        try (Connection c = Jdbc.openTarget(e); Statement st = c.createStatement()) {
            List<ProbeResult.Item> items = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("""
                    SELECT version(), current_database(), current_user,
                           pg_encoding_to_char(d.encoding), d.datcollate,
                           (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)
                    FROM pg_database d WHERE d.datname = current_database()""")) {
                rs.next();
                items.add(new ProbeResult.Item("버전", rs.getString(1)));
                items.add(new ProbeResult.Item("DB", rs.getString(2)));
                items.add(new ProbeResult.Item("로그인", rs.getString(3)));
                items.add(new ProbeResult.Item("인코딩 / 콜레이션", rs.getString(4) + " / " + rs.getString(5)));
                if (!"UTF8".equals(rs.getString(4))) {
                    warnings.add("대상 DB 인코딩이 UTF8 이 아니다");
                }
                if (rs.getBoolean(6)) {
                    warnings.add("superuser 로 접속했다. 전용 역할(kdms_app)을 쓴다(test/sql/pg/00_create_kdms_db.sql)");
                }
            }
            SchemaInstaller.Installed installed = SchemaInstaller.installedVersion(c);
            items.add(new ProbeResult.Item("관리 스키마 kdms", installed == null
                    ? "없음 (kdms init 으로 만든다)"
                    : "버전 " + installed.version() + " (" + installed.appliedAt() + ")"));
            if (installed != null && installed.version() < SchemaInstaller.CURRENT_VERSION) {
                warnings.add("관리 스키마가 옛 버전이다(" + installed.version() + " < " + SchemaInstaller.CURRENT_VERSION + "). kdms init");
            }
            return new ProbeResult("대상", e.toString(), true, ms(t0), List.copyOf(items), List.copyOf(warnings), null);
        } catch (SQLException ex) {
            return ProbeResult.failed("대상", e.toString(), ms(t0), ex.getMessage());
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).strip();
    }

    private static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (!b.isEmpty()) {
                    b.append(' ');
                }
                b.append(p);
            }
        }
        return b.toString();
    }
}
