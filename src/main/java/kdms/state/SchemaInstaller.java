package kdms.state;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대상 PG 에 관리 스키마(kdms)를 만들거나 최신 버전으로 올린다. Flyway 없이 버전 표 하나로 관리한다(plan.md §1.2).
 */
public final class SchemaInstaller {

    /** 버전 n 의 스크립트 = SCRIPTS.get(n - 1). */
    static final List<String> SCRIPTS = List.of("db/kdms-schema.sql");
    public static final int CURRENT_VERSION = SCRIPTS.size();

    /** 같은 DB 에서 init 이 동시에 돌지 않게(임의의 고정 키). */
    private static final long INIT_LOCK_KEY = 0x6B646D73_00000001L;

    public record Installed(int version, OffsetDateTime appliedAt) {
    }

    private SchemaInstaller() {
    }

    /** 설치된 버전. 관리 스키마가 없으면 null. 읽기만 한다. */
    public static Installed installedVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT to_regclass('kdms.schema_version') IS NOT NULL")) {
                rs.next();
                if (!rs.getBoolean(1)) {
                    return null;
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT version, applied_at FROM kdms.schema_version ORDER BY version DESC LIMIT 1")) {
                return rs.next() ? new Installed(rs.getInt(1), rs.getObject(2, OffsetDateTime.class)) : null;
            }
        }
    }

    /**
     * 빠진 버전 스크립트를 차례로 한 트랜잭션에서 적용한다.
     *
     * @return 적용 뒤 버전
     */
    public static int install(Connection c) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("SELECT pg_advisory_xact_lock(" + INIT_LOCK_KEY + ")");
            Installed now = installedVersion(c);
            int from = now == null ? 0 : now.version();
            if (from > CURRENT_VERSION) {
                throw new SQLException("관리 스키마 버전(" + from + ")이 이 프로그램(" + CURRENT_VERSION + ")보다 새 버전이다");
            }
            for (int v = from + 1; v <= CURRENT_VERSION; v++) {
                // PgJDBC 는 매개변수 없는 여러 문장을 한 번에 실행한다(simple query)
                st.execute(readScript(SCRIPTS.get(v - 1)));
            }
            c.commit();
            return CURRENT_VERSION;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    static String readScript(String resource) {
        try (InputStream in = SchemaInstaller.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("jar 안에 " + resource + " 가 없다");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(resource + " 를 읽지 못했다", e);
        }
    }
}
