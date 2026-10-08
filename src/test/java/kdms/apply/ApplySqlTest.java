package kdms.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.TablePlan;
import kdms.load.MockPlans;

class ApplySqlTest {

    @Test
    void 멱등_upsert_와_PK_삭제() {
        TablePlan code = MockPlans.table("code_master");
        ApplySql s = new ApplySql(code);
        assertThat(s.upsert).startsWith("INSERT INTO " + code.tgtQualified() + " (")
                .contains("ON CONFLICT (\"code_grp\", \"code\") DO UPDATE SET")
                .contains("\"code_nm\" = EXCLUDED.\"code_nm\"")
                .doesNotContain("\"code\" = EXCLUDED");
        assertThat(s.delete).isEqualTo("DELETE FROM " + code.tgtQualified() + " WHERE \"code_grp\" = CAST(? AS varchar(20)) AND \"code\" = CAST(? AS varchar(10))");
        assertThat(s.key).extracting(ColumnPlan::tgtName).containsExactly("code_grp", "code");
    }

    @Test
    void 계산_컬럼은_넣지_않는다_LOB_미변경은_UPDATE() {
        TablePlan doc = MockPlans.table("research_doc");
        ApplySql s = new ApplySql(doc);
        assertThat(s.columns).extracting(ColumnPlan::srcName).doesNotContain("file_ext");
        List<ColumnPlan> set = s.columns.stream().filter(c -> c.srcName().equals("view_cnt")).toList();
        assertThat(s.update(set)).isEqualTo("UPDATE " + doc.tgtQualified() + " SET \"view_cnt\" = CAST(? AS integer) WHERE \"doc_id\" = CAST(? AS integer)");
    }

    @Test
    void PK_없는_테이블은_거부() {
        TablePlan t = MockPlans.table("rating");
        TablePlan noPk = new TablePlan(t.srcSchema(), t.srcName(), t.tgtSchema(), t.tgtName(), t.rowsEstimate(), t.columns(), null,
                t.postLoad(), t.foreignKeys(), t.issues());
        assertThatThrownBy(() -> new ApplySql(noPk)).isInstanceOf(IllegalArgumentException.class);
    }
}
