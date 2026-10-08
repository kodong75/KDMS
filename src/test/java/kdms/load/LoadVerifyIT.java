package kdms.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import kdms.catalog.SourceCatalogReader;
import kdms.config.Connections;
import kdms.config.KdmsConfig;
import kdms.ddl.DdlWriter.Phase;
import kdms.ddl.SchemaApplier;
import kdms.ddl.SchemaPlan;
import kdms.ddl.SchemaPlanner;
import kdms.it.ItSupport;
import kdms.rules.Rules;
import kdms.rules.RulesLoader;
import kdms.verify.Verifier;

/**
 * 3단계 완료 기준(plan.md §6): 원천 KDMS_MOCK → 대상 PG 전체 적재 → 검증 일치, 중간에 실패한 구간만 다시 넣어 이어서 끝난다(T-L16).
 * 원천은 읽기만 한다. 대상은 시험 스키마 kdms_it_load·작업 kdms_it_load 만 쓰고 끝나면 지운다(사용자 작업 kdms_mock 은 건드리지 않는다).
 */
@Tag("integration")
class LoadVerifyIT {

    static final String NAME = "kdms_it_load";

    private final KdmsConfig base = ItSupport.config();
    private final KdmsConfig cfg = new KdmsConfig(NAME, base.source(), base.target(), base.load(), base.sync(), base.tables(), base.rules(), base.web());
    private final Connections db = Connections.of(cfg);
    private Rules rules;
    private SchemaPlan plan;

    @BeforeEach
    void setUp() throws Exception {
        Path f = Files.createTempFile("kdms-it-rules", ".yml");
        Files.writeString(f, Files.readString(Path.of("config/kdms-rules.yml")) + "\nidentifiers:\n  schemas: { dbo: " + NAME + " }\n");
        rules = RulesLoader.load(f);
        Files.delete(f);
        try (Connection src = db.source()) {
            plan = SchemaPlanner.plan(SourceCatalogReader.read(src), cfg.tables(), rules, null);
        }
        cleanUp();
        try (Connection c = db.target()) {
            SchemaApplier.apply(c, plan, Phase.PRE_LOAD, true, new SchemaApplier.Job(NAME, "it:1433", cfg.source().database(), "kdms", "it"));
        }
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection c = db.target(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + NAME + " CASCADE");
            st.execute("DO $$ BEGIN IF to_regclass('kdms.job') IS NOT NULL THEN DELETE FROM kdms.job WHERE job_name = '" + NAME + "'; END IF; END $$");
        }
    }

    @Test
    void 실패한_구간만_다시_넣어_끝나고_검증이_모두_일치한다() throws Exception {
        StringWriter log = new StringWriter();
        Loader loader = new Loader(cfg, rules, plan, db, new PrintWriter(log), "it");
        // 첫 실행: rating 의 마지막 구간을 커밋 직전에 실패시킨다(프로세스가 죽은 것과 같다: 그 구간 행은 커밋되지 않는다)
        AtomicBoolean failOnce = new AtomicBoolean(true);
        loader.hook = (t, chunk) -> {
            if (t.srcName().equals("rating") && chunk.upper() == null && failOnce.getAndSet(false)) {
                throw new SQLException("시험: 구간 중단");
            }
        };
        Loader.Result first = loader.run(new Loader.Options(Set.of(), false, true, 0, true));
        assertThat(first.allLoaded()).as(log.toString()).isFalse();
        assertThat(first.tables()).filteredOn(t -> t.status().equals("FAILED")).extracting(Loader.TableResult::srcTable).containsExactly("dbo.rating");
        assertThat(first.postLoad()).isNull(); // 실패가 있으면 적재 뒤 DDL 을 하지 않는다

        Loader.Result second = loader.run(new Loader.Options(Set.of(), false, true, 0, true));
        assertThat(second.allLoaded()).as(log.toString()).isTrue();
        Loader.TableResult rating = second.tables().stream().filter(t -> t.srcTable().equals("dbo.rating")).findFirst().orElseThrow();
        assertThat(rating.chunksSkipped()).isEqualTo(rating.chunks() - 1); // 끝난 구간은 건너뛰었다
        assertThat(second.tables()).filteredOn(t -> !t.srcTable().equals("dbo.rating")).allMatch(t -> t.status().equals("SKIPPED"));
        assertThat(second.postLoad()).as(log.toString()).startsWith("적재 뒤 DDL");

        Verifier.Result v = new Verifier(cfg, rules, plan, db).run(Set.of(), null);
        assertThat(v.tables()).allSatisfy(t -> assertThat(t.matched()).as(t.table().srcQualified() + " " + t.checks() + " " + t.error()).isTrue());
        assertThat(v.mismatches()).isZero();
        assertThat(v.checks()).isEqualTo(30); // KDMS_MOCK 7개 테이블: 건수 7 + 해시 7 + 합계 16

        // 대상 값을 하나 바꾸면 그 행의 PK 를 찾는다
        try (Connection c = db.target(); Statement st = c.createStatement()) {
            st.execute("UPDATE " + NAME + ".rating SET rating_cd = rating_cd || ' ' WHERE rating_id = 5");
            st.execute("DELETE FROM " + NAME + ".app_user WHERE user_id = 7");
        }
        Verifier.Result bad = new Verifier(cfg, rules, plan, db).run(Set.of("dbo.rating", "dbo.app_user"), null);
        assertThat(bad.mismatches()).isPositive();
        assertThat(bad.tables()).flatExtracting(Verifier.TableResult::rowDiffs)
                .containsExactlyInAnyOrder(new Verifier.RowDiff(java.util.List.of("5"), "diff"), new Verifier.RowDiff(java.util.List.of("7"), "missing"));
    }
}
