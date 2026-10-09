package kdms.cli;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;

import kdms.config.Connections;
import kdms.config.KdmsConfig;
import kdms.ddl.SchemaPlan;
import kdms.rules.Rules;
import kdms.verify.Verifier;
import kdms.verify.VerifyReport;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * 건수·합계·해시 검증(plan.md §4.7, KIS:docs/normalization.md). 원천·대상은 읽기만 하고 결과는 kdms.verify_* 에 남긴다.
 * 종료 코드: 0 모두 일치, 1 설정 오류, 2 접속 실패, 3 계획에 오류, 4 검증 거부(작업 없음 등), 5 불일치 있음.
 */
@Command(name = "verify", mixinStandardHelpOptions = true,
        description = "원천과 대상의 건수·수치 합계·행 해시를 비교한다. 다르면 어느 행(PK)이 다른지 찾는다. 데이터는 바꾸지 않는다.")
public class VerifyCommand implements Callable<Integer> {

    public static final int MISMATCH = 5;

    @Mixin
    ConfigOptions options;

    @Option(names = {"-t", "--table"}, paramLabel = "schema.table",
            description = "이 테이블만(원천 이름, 여러 번 쓸 수 있다). 없으면 계획의 테이블 전부")
    List<String> tables;

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
            err.println("계획에 오류가 있어 검증하지 않는다. kdms plan 보고서의 오류 절을 본다");
            return PlanSupport.BLOCKED;
        }
        out.println("KDMS " + VersionProvider.version() + " 검증 · 작업 " + cfg.jobName() + " · " + cfg.source() + " → " + cfg.target() + " · "
                + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")));
        out.println("규칙: KIS:docs/normalization.md (docs/load-verify.md §3) · 변환 규칙 " + StatusCommand.rulesSummary(cfg, rules));
        out.println();
        out.flush();
        Verifier.Result r;
        try {
            r = new Verifier(cfg, rules, plan, Connections.of(cfg)).run(LoadCommand.names(tables), t -> {
                out.println(VerifyReport.table(t));
                out.flush();
            });
        } catch (Verifier.Refused e) {
            err.println("검증하지 않음: " + e.getMessage());
            return SchemaCommand.REFUSED;
        } catch (SQLException e) {
            err.println("접속·조회 실패: " + e.getMessage());
            return StatusCommand.CONNECTION_FAILED;
        }
        out.println();
        out.println(VerifyReport.summary(r, plan));
        out.flush();
        return r.mismatches() == 0 ? 0 : MISMATCH;
    }
}
