package kdms.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import kdms.catalog.DbProbe;
import kdms.catalog.ProbeResult;
import kdms.config.Jdbc;
import kdms.config.KdmsConfig;
import kdms.state.SchemaInstaller;

/** 대상 PG: 접속·버전, 관리 스키마 설치가 재실행 안전한지. */
@Tag("integration")
class TargetSchemaIT {

    private final KdmsConfig cfg = ItSupport.config();

    @Test
    void 대상은_PostgreSQL_16_이고_UTF8() {
        ProbeResult r = DbProbe.probeTarget(cfg.target());
        assertThat(r.connected()).as(r.error()).isTrue();
        assertThat(value(r, "버전")).startsWith("PostgreSQL 16.");
        assertThat(value(r, "인코딩 / 콜레이션")).startsWith("UTF8 /");
    }

    @Test
    void 관리_스키마_설치는_두번_해도_같다() throws Exception {
        try (Connection c = Jdbc.openTarget(cfg.target())) {
            assertThat(SchemaInstaller.install(c)).isEqualTo(SchemaInstaller.CURRENT_VERSION);
            assertThat(SchemaInstaller.install(c)).isEqualTo(SchemaInstaller.CURRENT_VERSION);
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT count(*) FILTER (WHERE c.relkind = 'r'),
                                (SELECT count(*) FROM kdms.schema_version)
                         FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'kdms'
                           AND c.relname NOT LIKE 'debezium%'""")) {
                rs.next();
                assertThat(rs.getInt(1)).as("관리 테이블 수").isEqualTo(10);
                assertThat(rs.getInt(2)).as("schema_version 행 수").isEqualTo(SchemaInstaller.CURRENT_VERSION);
            }
        }
    }

    static String value(ProbeResult r, String name) {
        return r.items().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow().value();
    }
}
