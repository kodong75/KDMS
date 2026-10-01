package kdms.ddl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import kdms.config.Jdbc;
import kdms.config.KdmsConfig;
import kdms.ddl.DdlWriter.Phase;
import kdms.it.ItSupport;
import kdms.rules.RulesLoader;

/**
 * 대상 PG: KDMS_MOCK 계획(시험 고정 카탈로그)의 DDL 을 실제로 적용해 본다. 원천 MS-SQL 은 필요 없다.
 * 시험 스키마 kdms_it_ddl·작업 kdms_it_ddl 만 쓰고 끝나면 지운다.
 */
@Tag("integration")
class TargetDdlIT {

    static final String SCHEMA = "kdms_it_ddl";

    private final KdmsConfig cfg = ItSupport.config();
    private final SchemaApplier.Job job = new SchemaApplier.Job(SCHEMA, "it:1433", "KDMS_MOCK", "kdms", "0".repeat(64));
    private SchemaPlan plan;

    @BeforeEach
    void setUp() throws Exception {
        // 저장소 규칙(config/kdms-rules.yml) + 스키마만 시험용으로
        Path rules = Files.createTempFile("kdms-it-rules", ".yml");
        Files.writeString(rules, Files.readString(Path.of("config/kdms-rules.yml"))
                + "\nidentifiers:\n  schemas: { dbo: " + SCHEMA + " }\n");
        plan = SchemaPlanner.plan(KdmsMockCatalog.catalog(), SchemaPlannerTest.ALL, RulesLoader.load(rules), null);
        Files.delete(rules);
        cleanUp();
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection c = Jdbc.openTarget(cfg.target()); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            st.execute("DO $$ BEGIN IF to_regclass('kdms.job') IS NOT NULL THEN DELETE FROM kdms.job WHERE job_name = '" + SCHEMA + "'; END IF; END $$");
        }
    }

    @Test
    void 세_단계를_적용하면_KIS_mock_sql_과_같은_타입이고_규칙대로_동작한다() throws SQLException {
        try (Connection c = Jdbc.openTarget(cfg.target())) {
            SchemaApplier.Result r = SchemaApplier.apply(c, plan, Phase.PRE_LOAD, false, job);
            SchemaApplier.apply(c, plan, Phase.POST_LOAD, false, job);
            SchemaApplier.apply(c, plan, Phase.CUTOVER, false, job);
            assertThat(r.jobId()).isPositive();

            // 대상 카탈로그에서 읽은 타입 = 계획의 타입 = KIS mock.sql 타입(SchemaPlannerTest)
            Map<String, String> actual = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT c.relname, string_agg(a.attname || ' ' || format_type(a.atttypid, a.atttypmod), ', ' ORDER BY a.attnum)
                    FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped
                    GROUP BY c.relname ORDER BY c.relname""")) {
                ps.setString(1, SCHEMA);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        actual.put(rs.getString(1), rs.getString(2).replace("character varying", "varchar")
                                .replace("timestamp(3) without time zone", "timestamp(3)").replace("timestamp(6) without time zone", "timestamp(6)")
                                .replace("character(", "char(").replace(",4)", ",4)"));
                    }
                }
            }
            Map<String, String> planned = new LinkedHashMap<>();
            plan.tables().forEach(t -> planned.put(t.tgtName(),
                    String.join(", ", t.columns().stream().map(col -> col.tgtName() + " " + col.tgtType()).toList())));
            assertThat(actual).isEqualTo(planned);

            try (Statement st = c.createStatement()) {
                st.execute("SET search_path = " + SCHEMA);
                // B14: CI UNIQUE → lower() 유일 인덱스
                st.execute("INSERT INTO app_user (login_id, user_nm) VALUES ('Kim01', '김')");
                assertThatThrownBy(() -> st.execute("INSERT INTO app_user (login_id, user_nm) VALUES ('kim01', '김')"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("uq_app_user_login");
                // tinyint → smallint CHECK
                assertThatThrownBy(() -> st.execute("INSERT INTO app_user (login_id, user_nm, member_level) VALUES ('x', 'x', 256)"))
                        .hasMessageContaining("check constraint");
                // 기본값·IDENTITY·계산 컬럼·시퀀스 기본값(B12·B13)
                st.execute("INSERT INTO issuer (issuer_cd, issuer_nm, row_ver) VALUES ('000001', '가나다', '\\x00')");
                st.execute("INSERT INTO rating (issuer_id, rating_cd, rating_dt, eff_dtm) SELECT issuer_id, 'aa+ ', DATE '2026-10-01', LOCALTIMESTAMP FROM issuer");
                st.execute("INSERT INTO research_doc (doc_type_cd, title, file_nm, pub_dtm) VALUES ('01', '제목', 'CI20260916-2.PDF', LOCALTIMESTAMP)");
                try (ResultSet rs = st.executeQuery("""
                        SELECT i.is_listed, i.issuer_guid IS NOT NULL, i.reg_dtm IS NOT NULL, r.rating_rank, r.is_watch,
                               d.doc_no, d.file_ext, d.view_cnt
                        FROM issuer i JOIN rating r ON r.issuer_id = i.issuer_id CROSS JOIN research_doc d""")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean(1)).isFalse();
                    assertThat(rs.getBoolean(2)).isTrue();
                    assertThat(rs.getBoolean(3)).isTrue();
                    assertThat(rs.getInt(4)).isEqualTo(2);           // 'aa+ ' → AA+ → 2
                    assertThat(rs.getBoolean(5)).isFalse();
                    assertThat(rs.getLong(6)).isEqualTo(202600001L); // 시퀀스 시작값(setval 은 전환 때)
                    assertThat(rs.getString(7)).isEqualTo("pdf");
                    assertThat(rs.getInt(8)).isZero();
                }
                // FK(전환 단계)
                assertThatThrownBy(() -> st.execute("INSERT INTO rating (issuer_id, rating_cd, rating_dt, eff_dtm) VALUES (999, 'A', DATE '2026-10-01', LOCALTIMESTAMP)"))
                        .hasMessageContaining("fk_rating_issuer");
                st.execute("RESET search_path");
            }

            // 작업 등록
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT j.status, count(t.*), count(*) FILTER (WHERE t.has_pk)
                    FROM kdms.job j JOIN kdms.job_table t ON t.job_id = j.job_id WHERE j.job_name = ? GROUP BY j.status""")) {
                ps.setString(1, SCHEMA);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("SCHEMA_DONE");
                    assertThat(rs.getInt(2)).isEqualTo(8);
                    assertThat(rs.getInt(3)).isEqualTo(8);
                }
            }
        }
    }

    @Test
    void 이미_있으면_거부하고_replace_면_다시_만들고_적재가_시작됐으면_거부() throws SQLException {
        try (Connection c = Jdbc.openTarget(cfg.target())) {
            SchemaApplier.apply(c, plan, Phase.PRE_LOAD, false, job);
            assertThatThrownBy(() -> SchemaApplier.apply(c, plan, Phase.PRE_LOAD, false, job))
                    .isInstanceOf(SchemaApplier.Refused.class).hasMessageContaining("--replace");
            SchemaApplier.Result r = SchemaApplier.apply(c, plan, Phase.PRE_LOAD, true, job);
            assertThat(r.dropped()).hasSize(9); // 테이블 8 + 시퀀스 1

            try (Statement st = c.createStatement()) {
                st.execute("UPDATE kdms.job SET status = 'LOADING' WHERE job_name = '" + SCHEMA + "'");
            }
            assertThatThrownBy(() -> SchemaApplier.apply(c, plan, Phase.PRE_LOAD, true, job))
                    .isInstanceOf(SchemaApplier.Refused.class).hasMessageContaining("LOADING");
        }
    }

    @Test
    void 중간에_실패하면_아무것도_남지_않는다() throws SQLException {
        SchemaPlan broken = new SchemaPlan(plan.sourceDb(), plan.sourceCollation(),
                java.util.List.of(plan.tables().get(0), plan.tables().get(0)), // 같은 테이블 두 번 → 두 번째 CREATE 실패
                plan.excluded(), plan.sequences(), plan.collations(), plan.issues(), plan.manualObjects());
        try (Connection c = Jdbc.openTarget(cfg.target())) {
            assertThatThrownBy(() -> SchemaApplier.apply(c, broken, Phase.PRE_LOAD, false, job))
                    .isInstanceOf(SQLException.class).hasMessageContaining("실패한 문장");
            assertThat(SchemaApplier.existing(c, plan)).isEmpty();
        }
    }
}
