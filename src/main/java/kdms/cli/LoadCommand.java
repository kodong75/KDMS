package kdms.cli;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

import kdms.config.Connections;
import kdms.config.KdmsConfig;
import kdms.ddl.SchemaPlan;
import kdms.load.Loader;
import kdms.rules.Rules;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * 전체 적재(plan.md §4.3). kdms schema 로 만든 대상 테이블에 원천을 COPY 한다. 다시 실행하면 끝난 구간은 건너뛴다.
 * 종료 코드: 0 모두 적재, 1 설정 오류, 2 접속 실패·적재 뒤 DDL 실패, 3 계획에 오류, 4 적재 거부(작업 없음·다른 적재 실행 중 등), 5 실패한 테이블 있음.
 */
@Command(name = "load", mixinStandardHelpOptions = true,
        description = "원천 테이블을 대상 PG 에 전체 적재한다(COPY, 테이블·구간 병렬). 중간에 멈춰도 다시 실행하면 이어서 한다.")
public class LoadCommand implements Callable<Integer> {

    public static final int FAILED_TABLES = 5;

    @Mixin
    ConfigOptions options;

    @Option(names = {"-t", "--table"}, paramLabel = "schema.table",
            description = "이 테이블만(원천 이름, 여러 번 쓸 수 있다). 없으면 계획의 테이블 전부")
    List<String> tables;

    @Option(names = "--reset", description = "고른 대상 테이블을 비우고(TRUNCATE) 구간 기록을 지운 뒤 처음부터 적재한다")
    boolean reset;

    @Option(names = "--no-post-load", description = "모두 적재돼도 적재 뒤 DDL(UNIQUE·인덱스)을 적용하지 않는다")
    boolean noPostLoad;

    @Option(names = "--throttle-ms", paramLabel = "ms", defaultValue = "0",
            description = "구간마다 1,000행 읽을 때마다 쉬는 시간. 원천 부하를 줄이거나 중단·재시작 시험에 쓴다 (기본값: ${DEFAULT-VALUE})")
    long throttleMs;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        KdmsConfig cfg = options.loadConfig();
        Rules rules = options.loadRules(cfg);
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        SchemaPlan plan;
        try {
            plan = new PlanSupport().plan(cfg, rules);
        } catch (SQLException e) {
            err.println("원천 접속·조회 실패 (" + cfg.source() + "): " + e.getMessage());
            return StatusCommand.CONNECTION_FAILED;
        }
        if (plan.blocked()) {
            err.println("계획에 오류가 있어 적재하지 않는다. kdms plan 보고서의 오류 절을 본다");
            return PlanSupport.BLOCKED;
        }
        out.println("KDMS " + VersionProvider.version() + " 전체 적재 · 작업 " + cfg.jobName() + " · " + cfg.source() + " → " + cfg.target()
                + " · 병렬 테이블 " + cfg.load().tableParallelism() + " × 구간 " + cfg.load().chunksPerTable() + " · 원천 격리 " + cfg.load().isolation());
        Loader.Result r;
        try {
            r = new Loader(cfg, rules, plan, Connections.of(cfg), out, options.configSha256(cfg))
                    .run(new Loader.Options(names(tables), reset, !noPostLoad, throttleMs));
        } catch (Loader.Refused e) {
            err.println("적재하지 않음: " + e.getMessage());
            return SchemaCommand.REFUSED;
        } catch (SQLException e) {
            err.println("대상 접속·상태 기록 실패 (" + cfg.target() + "): " + e.getMessage());
            return StatusCommand.CONNECTION_FAILED;
        }
        long loaded = r.tables().stream().filter(t -> !"FAILED".equals(t.status())).count();
        long rows = r.tables().stream().mapToLong(Loader.TableResult::rows).sum();
        out.println();
        out.println("결과: 테이블 " + r.tables().size() + "개 중 적재 " + loaded + "개(이미 적재돼 건너뜀 "
                + r.tables().stream().filter(t -> "SKIPPED".equals(t.status())).count() + "), 실패 " + (r.tables().size() - loaded)
                + "개, 대상 행 " + String.format("%,d", rows));
        if (r.postLoad() != null) {
            out.println(r.postLoad());
        }
        if (!r.allLoaded()) {
            out.println("실패한 구간만 다시 하려면 같은 명령을 다시 실행한다(끝난 구간은 건너뛴다)");
        } else {
            out.println("다음: kdms verify");
        }
        out.flush();
        return !r.allLoaded() ? FAILED_TABLES : r.postLoadFailed() ? StatusCommand.CONNECTION_FAILED : 0;
    }

    static Set<String> names(List<String> tables) {
        Set<String> out = new LinkedHashSet<>();
        if (tables != null) {
            tables.forEach(t -> out.add(t.strip().toLowerCase(Locale.ROOT)));
        }
        return out;
    }
}
