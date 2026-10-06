package kdms.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import kdms.catalog.SourceCatalog;
import kdms.catalog.SourceCatalogReader;
import kdms.catalog.SourceDataScanner;
import kdms.config.Jdbc;
import kdms.config.KdmsConfig;
import kdms.ddl.DdlWriter;
import kdms.ddl.PlanReport;
import kdms.ddl.SchemaPlan;
import kdms.ddl.SchemaPlanner;
import kdms.rules.Rules;
import picocli.CommandLine.Option;

/** plan·schema 명령 공통: 원천 카탈로그 읽기 → 계획 → 보고서·DDL 파일 쓰기. */
public class PlanSupport {

    /** 계획에 오류가 있음 */
    public static final int BLOCKED = 3;

    @Option(names = "--scan", description = "원천 데이터를 테이블마다 한 번 훑어 NUL 문자(A02)·센티널 날짜(A08) 행 수를 센다. 큰 DB 는 오래 걸린다")
    boolean scan;

    @Option(names = {"-o", "--out"}, paramLabel = "폴더",
            description = "보고서(plan.txt)와 DDL(10_pre_load.sql, 20_post_load.sql, 30_cutover.sql)을 쓸 폴더 (기본값: out/<작업 이름>)")
    Path out;

    /** 원천에 접속해 계획을 만든다 */
    SchemaPlan plan(KdmsConfig cfg, Rules rules) throws SQLException {
        try (Connection c = Jdbc.openSource(cfg.source())) {
            SourceCatalog catalog = SourceCatalogReader.read(c);
            Map<String, SourceDataScanner.TableScan> scanned = null;
            if (scan) {
                SchemaPlan first = SchemaPlanner.plan(catalog, cfg.tables(), rules, null);
                List<SourceCatalog.Table> tables = catalog.tables().stream()
                        .filter(t -> first.tables().stream().anyMatch(p -> p.srcSchema().equals(t.schema()) && p.srcName().equals(t.name())))
                        .toList();
                scanned = SourceDataScanner.scan(c, tables, rules.sentinelValues());
            }
            return SchemaPlanner.plan(catalog, cfg.tables(), rules, scanned);
        }
    }

    Path outDir(KdmsConfig cfg) {
        return out != null ? out : Path.of("out", cfg.jobName());
    }

    /** 보고서·DDL 파일을 쓰고 보고서 본문을 돌려준다 */
    String write(SchemaPlan plan, KdmsConfig cfg, Rules rules, PrintWriter console) throws IOException {
        Path dir = outDir(cfg);
        Files.createDirectories(dir);
        String report = PlanReport.render(plan, List.of(
                "KDMS " + VersionProvider.version() + " 스키마 변환 계획 · 작업 " + cfg.jobName() + " · "
                        + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")),
                "변환 규칙: " + StatusCommand.rulesSummary(cfg, rules)
                        + (scan ? " · 데이터 검사(--scan) 함" : " · 데이터 검사 안 함(NUL·센티널 건수는 --scan)")));
        Files.writeString(dir.resolve("plan.txt"), report, StandardCharsets.UTF_8);
        for (Map.Entry<DdlWriter.Phase, String> e : DdlWriter.all(plan).entrySet()) {
            Files.writeString(dir.resolve(e.getKey().fileName), e.getValue(), StandardCharsets.UTF_8);
        }
        console.println("파일: " + dir.resolve("plan.txt") + ", " + DdlWriter.Phase.PRE_LOAD.fileName + ", "
                + DdlWriter.Phase.POST_LOAD.fileName + ", " + DdlWriter.Phase.CUTOVER.fileName);
        return report;
    }
}
