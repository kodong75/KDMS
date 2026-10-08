package kdms.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** DB 없이 관리 테이블 DDL 의 형태만 본다. 실제 적용은 TargetSchemaIT. */
class SchemaScriptTest {

    private final String sql = SchemaInstaller.readScript(SchemaInstaller.SCRIPTS.get(0));
    private final String all = SchemaInstaller.SCRIPTS.stream().map(SchemaInstaller::readScript).collect(java.util.stream.Collectors.joining("\n"));

    @Test
    void 모든_CREATE_는_재실행_안전() {
        Matcher m = Pattern.compile("(?im)^\\s*CREATE\\s+(SCHEMA|TABLE|INDEX|UNIQUE INDEX)\\s+(?!IF NOT EXISTS)").matcher(all);
        assertThat(m.find()).as("IF NOT EXISTS 없는 CREATE").isFalse();
        Matcher add = Pattern.compile("(?im)ADD\\s+COLUMN\\s+(?!IF NOT EXISTS)").matcher(all);
        assertThat(add.find()).as("IF NOT EXISTS 없는 ADD COLUMN").isFalse();
    }

    @Test
    void plan_4_2_의_관리_테이블이_모두_있다() {
        for (String t : new String[] {"schema_version", "job", "job_table", "load_chunk", "watermark", "change_log",
                "verify_run", "verify_result", "verify_row_diff", "event_log"}) {
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS kdms." + t + " (");
        }
    }

    @Test
    void 버전마다_자기_버전_행을_넣는다() {
        for (int v = 1; v <= SchemaInstaller.CURRENT_VERSION; v++) {
            assertThat(SchemaInstaller.readScript(SchemaInstaller.SCRIPTS.get(v - 1)))
                    .contains("INSERT INTO kdms.schema_version").contains("VALUES (" + v + ",");
        }
    }

    @Test
    void change_log_는_LSN_위치로_중복을_막는다() {
        assertThat(sql).contains("UNIQUE (job_id, commit_lsn, change_lsn, event_serial_no)");
    }
}
