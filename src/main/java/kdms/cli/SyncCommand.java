package kdms.cli;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kdms.cdc.SyncRunner;
import kdms.config.Connections;
import kdms.config.KdmsConfig;
import kdms.ddl.SchemaPlan;
import kdms.rules.Rules;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * 변경분 동기화(plan.md §4.3 ①·§4.4, docs/cdc.md). 원천 CDC 변경을 받아(Debezium Embedded) 적재가 끝난 테이블에 반영한다.
 * 처음 실행하면 워터마크를 기록하고, 그 뒤에 다른 터미널에서 kdms load 를 실행한다. Ctrl+C 로 멈추고 다시 실행하면 이어 받는다.
 * 종료 코드: 0 중지·따라잡음(--drain), 1 설정 오류, 2 접속 실패, 3 계획에 오류, 4 시작 거부, 5 수집·반영 실패.
 */
@Command(name = "sync", mixinStandardHelpOptions = true,
        description = "원천 변경분(MS-SQL CDC)을 받아 대상 PG 에 반영한다. 먼저 이것을 띄우고 '워터마크 기록'이 나온 뒤 kdms load 를 실행한다.")
public class SyncCommand implements Callable<Integer> {

    @Mixin
    ConfigOptions options;

    @Option(names = "--drain", description = "원천 쓰기를 멈춘 뒤에 쓴다. 지금까지의 변경을 모두 반영하면 끝낸다(검증·전환 전)")
    boolean drain;

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
            err.println("계획에 오류가 있어 동기화하지 않는다. kdms plan 보고서의 오류 절을 본다");
            return PlanSupport.BLOCKED;
        }
        SyncRunner runner = new SyncRunner(cfg, rules, plan, Connections.of(cfg), out);
        // Ctrl+C(SIGINT)·SIGTERM: 진행 중 배치를 마치고 엔진을 닫을 때까지 기다린다(main 스레드는 System.exit 에서 멈추므로 latch 로 기다린다)
        CountDownLatch done = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            runner.requestStop();
            try {
                done.await(90, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "kdms-sync-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return runner.run(new SyncRunner.Options(drain));
        } catch (SyncRunner.Refused e) {
            err.println("동기화하지 않음: " + e.getMessage());
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
