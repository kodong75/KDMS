package kdms.ddl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import kdms.catalog.SourceCatalog;
import kdms.catalog.SourceCatalogReader;
import kdms.catalog.SourceDataScanner;
import kdms.config.Jdbc;
import kdms.config.KdmsConfig;
import kdms.it.ItSupport;
import kdms.rules.Rules;
import kdms.rules.RulesLoader;

/**
 * 원천 MS-SQL(KDMS_MOCK): 카탈로그 조회 SQL 이 실제 서버에서 돌고, 손으로 옮긴 시험 고정값(KdmsMockCatalog)과 같은지.
 * 같으면 단위 시험(SchemaPlannerTest)의 결론이 실제 원천에도 그대로 맞는다.
 */
@Tag("integration")
class SourceCatalogIT {

    private final KdmsConfig cfg = ItSupport.config();
    private final Rules rules = RulesLoader.load(Path.of("config/kdms-rules.yml"));

    @Test
    void KDMS_MOCK_카탈로그는_시험_고정값과_같고_DDL_도_같다() throws Exception {
        SourceCatalog fixture = KdmsMockCatalog.catalog();
        SourceCatalog real;
        try (Connection c = Jdbc.openSource(cfg.source())) {
            real = SourceCatalogReader.read(c);
        }
        assertThat(real.database()).isEqualTo(cfg.source().database());
        assertThat(real.collation()).isEqualTo(fixture.collation());
        assertThat(real.tables()).extracting(SourceCatalog.Table::qualifiedName)
                .containsExactlyElementsOf(fixture.tables().stream().map(SourceCatalog.Table::qualifiedName).toList());
        for (SourceCatalog.Table f : fixture.tables()) {
            SourceCatalog.Table r = real.tables().stream().filter(x -> x.qualifiedName().equals(f.qualifiedName())).findFirst().orElseThrow();
            assertThat(r.columns()).as(f.name() + " 컬럼").usingRecursiveFieldByFieldElementComparatorIgnoringFields("identity.lastValue")
                    .containsExactlyElementsOf(f.columns());
            assertThat(r.indexes()).as(f.name() + " 인덱스").containsExactlyInAnyOrderElementsOf(f.indexes());
            assertThat(r.foreignKeys()).as(f.name() + " FK").containsExactlyElementsOf(f.foreignKeys());
            assertThat(r.checks()).as(f.name() + " CHECK").isEmpty();
            assertThat(r.triggers()).as(f.name() + " 트리거").containsExactlyElementsOf(f.triggers());
        }
        assertThat(real.sequences()).usingRecursiveFieldByFieldElementComparatorIgnoringFields("current", "lastUsed")
                .containsExactlyElementsOf(fixture.sequences());
        assertThat(real.otherObjects()).extracting(SourceCatalog.DbObject::typeDesc)
                .containsExactlyInAnyOrderElementsOf(fixture.otherObjects().stream().map(SourceCatalog.DbObject::typeDesc).toList());

        SchemaPlan pr = SchemaPlanner.plan(real, cfg.tables(), rules, null);
        SchemaPlan pf = SchemaPlanner.plan(fixture, cfg.tables(), rules, null);
        for (DdlWriter.Phase phase : DdlWriter.Phase.values()) {
            assertThat(DdlWriter.write(pr, phase)).as(phase.name()).isEqualTo(DdlWriter.write(pf, phase));
        }
    }

    @Test
    void 데이터_검사는_KIS_가_심은_NUL_과_센티널을_찾는다() throws Exception {
        try (Connection c = Jdbc.openSource(cfg.source())) {
            SourceCatalog real = SourceCatalogReader.read(c);
            Map<String, SourceDataScanner.TableScan> scan = SourceDataScanner.scan(c, real.tables(), rules.sentinelValues());
            assertThat(scan.get("dbo.issuer").columns().get("issuer_nm").nulRows()).as("A02 NUL").isPositive();
            assertThat(scan.get("dbo.research_doc").columns().get("pub_dtm").sentinelRows()).as("A08 센티널").isGreaterThanOrEqualTo(2);
            assertThat(scan.get("dbo.app_user").rows()).isPositive();
            SchemaPlan defaults = SchemaPlanner.plan(real, cfg.tables(), kdms.rules.RulesLoader.load(null), scan);
            assertThat(defaults.allIssues()).filteredOn(i -> i.level() == SchemaPlan.Issue.Level.ERROR).extracting(SchemaPlan.Issue::where)
                    .as("기본 nul_char: fail 이면 NUL 컬럼만 오류(T-L09)").contains("dbo.issuer.issuer_nm");
            // 저장소 규칙(3단계 결정): issuer_nm 은 replace → 오류가 아니라 경고
            SchemaPlan p = SchemaPlanner.plan(real, cfg.tables(), rules, scan);
            assertThat(p.blocked()).isFalse();
            assertThat(p.allIssues()).filteredOn(i -> i.where().equals("dbo.issuer.issuer_nm") && i.message().contains("NUL"))
                    .extracting(SchemaPlan.Issue::level).containsExactly(SchemaPlan.Issue.Level.WARN);
        }
    }
}
