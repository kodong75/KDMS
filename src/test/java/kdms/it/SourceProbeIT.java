package kdms.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import kdms.catalog.DbProbe;
import kdms.catalog.ProbeResult;
import kdms.config.KdmsConfig;

/** 원천 MS-SQL: 0단계 준비(test/sql/mssql)가 끝났는지. */
@Tag("integration")
class SourceProbeIT {

    private final KdmsConfig cfg = ItSupport.config();

    @Test
    void 원천은_SQL_Server_2019_이고_CDC_스냅숏_Agent_준비됨() {
        ProbeResult r = DbProbe.probeSource(cfg.source());
        assertThat(r.connected()).as(r.error()).isTrue();
        assertThat(TargetSchemaIT.value(r, "버전")).startsWith("Microsoft SQL Server 2019");
        assertThat(TargetSchemaIT.value(r, "DB")).isEqualTo(cfg.source().database());
        assertThat(TargetSchemaIT.value(r, "CDC")).startsWith("켜짐");
        assertThat(TargetSchemaIT.value(r, "스냅숏 격리")).isEqualTo("ON");
        assertThat(TargetSchemaIT.value(r, "SQL Agent")).isEqualTo("Running");
        assertThat(r.warnings()).isEmpty();
    }
}
