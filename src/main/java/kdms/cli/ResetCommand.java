package kdms.cli;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import kdms.cdc.CaptureStore;
import kdms.config.Connections;
import kdms.config.KdmsConfig;
import kdms.state.SchemaInstaller;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * 작업을 적재 전(SCHEMA_DONE)으로 되돌린다: 대상 테이블을 비우고 구간 기록·워터마크·change_log·Debezium 오프셋을 지운다.
 * CDC 보존 기간 초과(T-C10)·워터마크 분실 뒤 처음부터 다시 할 때 쓴다. 테이블 정의는 그대로 둔다.
 * 종료 코드: 0 완료, 1 설정 오류, 2 접속 실패, 4 거부(--yes 없음, 실행 중인 sync·load, 작업 없음).
 */
@Command(name = "reset", mixinStandardHelpOptions = true,
        description = "작업의 적재·동기화 상태를 지우고 대상 테이블을 비운다(테이블 정의는 남긴다). 다음은 kdms sync → kdms load.")
public class ResetCommand implements Callable<Integer> {

    @Mixin
    ConfigOptions options;

    @Option(names = "--yes", description = "대상 테이블의 데이터를 지운다는 확인")
    boolean yes;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        KdmsConfig cfg = options.loadConfig();
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (!yes) {
            err.println("대상 테이블의 데이터와 적재·동기화 기록을 지운다. 맞으면 --yes 를 붙여 다시 실행한다");
            return SchemaCommand.REFUSED;
        }
        try (Connection c = Connections.of(cfg).target()) {
            SchemaInstaller.install(c);
            String running = CaptureStore.running(c, cfg.jobName());
            if (running != null) {
                err.println("되돌리지 않음: " + running + " 이 작업 " + cfg.jobName() + " 을 실행하고 있다. 멈춘 뒤 다시 한다");
                return SchemaCommand.REFUSED;
            }
            long jobId;
            List<String> tables = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT job_id FROM kdms.job WHERE job_name = ?")) {
                ps.setString(1, cfg.jobName());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        err.println("되돌리지 않음: 작업 " + cfg.jobName() + " 이 없다");
                        return SchemaCommand.REFUSED;
                    }
                    jobId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT quote_ident(tgt_schema) || '.' || quote_ident(tgt_table) FROM kdms.job_table
                    WHERE job_id = ? AND to_regclass(quote_ident(tgt_schema) || '.' || quote_ident(tgt_table)) IS NOT NULL
                    ORDER BY job_table_id""")) {
                ps.setLong(1, jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                }
            }
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                if (!tables.isEmpty()) {
                    st.execute("TRUNCATE " + String.join(", ", tables));
                }
                CaptureStore.clear(c, jobId);
                for (String sql : new String[] {
                        "DELETE FROM kdms.load_chunk WHERE job_table_id IN (SELECT job_table_id FROM kdms.job_table WHERE job_id = ?)",
                        """
                        UPDATE kdms.job_table SET status = 'PENDING', rows_loaded = NULL, load_started_at = NULL, load_finished_at = NULL,
                               last_error = NULL WHERE job_id = ? AND status <> 'EXCLUDED'""",
                        "UPDATE kdms.job SET status = 'SCHEMA_DONE', last_error = NULL, updated_at = now() WHERE job_id = ?",
                        "INSERT INTO kdms.event_log (job_id, level, stage, message) VALUES (?, 'WARN', 'reset', 'reset: 대상 테이블을 비우고 적재·동기화 기록을 지웠다')"}) {
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setLong(1, jobId);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
            out.println("작업 " + cfg.jobName() + " 을 적재 전(SCHEMA_DONE)으로 되돌렸다: 대상 테이블 " + tables.size()
                    + "개 비움, 워터마크·변경 기록·Debezium 오프셋 삭제");
            out.println("다음: kdms sync (워터마크 기록) → 다른 터미널에서 kdms load");
            return 0;
        }
    }
}
