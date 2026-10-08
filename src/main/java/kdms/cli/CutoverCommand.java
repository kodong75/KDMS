package kdms.cli;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kdms.config.Connections;
import kdms.config.KdmsConfig;
import kdms.cutover.CutoverRunner;
import kdms.ddl.SchemaPlan;
import kdms.rules.Rules;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * 전환(plan.md §4.5, docs/cutover.md). 원천 쓰기를 멈춘 뒤 마지막 반영 → PK 없는 테이블 재적재 → UNIQUE·인덱스 → 검증 → setval → FK.
 * 종료 코드: 0 전환 끝, 1 설정 오류, 2 접속 실패, 3 계획에 오류, 4 시작 거부, 5 검증 불일치·단계 실패, 6 마지막 반영 시간 초과.
 */
@Command(name = "cutover", mixinStandardHelpOptions = true,
        description = "원천 쓰기를 멈춘 뒤 전환한다: 마지막 반영 → 검증 → IDENTITY·SEQUENCE 다음 값 → FK. 걸린 시간이 예상 다운타임이다.")
public class CutoverCommand implements Callable<Integer> {

    @Mixin
    ConfigOptions options;

    @Option(names = "--yes", description = "원천 앱 쓰기를 멈췄다는 확인(KDMS 는 앱을 대신 멈추지 않는다)")
    boolean yes;

    @Option(names = "--no-cdc", description = "kdms load --no-cdc 로 적재한 작업(원천 쓰기 없음, 워터마크 없음): 마지막 반영을 건너뛴다")
    boolean noCdc;

    @Option(names = "--max-wait", paramLabel = "초", defaultValue = "600",
            description = "마지막 반영이 이 시간 안에 끝나지 않으면 멈춘다(원천 쓰기가 아직 있을 때) (기본값: ${DEFAULT-VALUE})")
    long maxWait;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private volatile CutoverRunner runner;

    /** 웹 화면의 "중지": Ctrl+C 와 같다(진행 중 단계를 마치고 멈춘다) */
    public void requestStop() {
        CutoverRunner r = runner;
        if (r != null) {
            r.requestStop();
        }
    }

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (!yes) {
            err.println("전환은 원천 앱 쓰기를 멈춘 뒤에 한다. 멈췄으면 --yes 를 붙여 다시 실행한다");
            return SchemaCommand.REFUSED;
        }
        KdmsConfig cfg = options.loadConfig();
        Rules rules = options.loadRules(cfg);
        SchemaPlan plan;
        try {
            plan = new PlanSupport().plan(cfg, rules);
        } catch (SQLException e) {
            err.println("원천 접속·조회 실패 (" + cfg.source() + "): " + e.getMessage());
            return StatusCommand.CONNECTION_FAILED;
        }
        if (plan.blocked()) {
            err.println("계획에 오류가 있어 전환하지 않는다. kdms plan 보고서의 오류 절을 본다");
            return PlanSupport.BLOCKED;
        }
        CutoverRunner runner = new CutoverRunner(cfg, rules, plan, Connections.of(cfg), out, options.configSha256(cfg));
        this.runner = runner;
        // Ctrl+C: 진행 중 단계를 마치고(마지막 반영은 배치를 마치고) 멈춘다. SyncCommand 와 같은 방식
        CountDownLatch done = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            runner.requestStop();
            try {
                done.await(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "kdms-cutover-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return runner.run(new CutoverRunner.Options(noCdc, maxWait)).code();
        } catch (CutoverRunner.Refused e) {
            err.println("전환하지 않음: " + e.getMessage());
            return SchemaCommand.REFUSED;
        } catch (SQLException e) {
            err.println("접속·상태 기록 실패: " + e.getMessage());
            return StatusCommand.CONNECTION_FAILED;
        } finally {
            done.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // 종료 중
            }
            out.flush();
        }
    }
}
