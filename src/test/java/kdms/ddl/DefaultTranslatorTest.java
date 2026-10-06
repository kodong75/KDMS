package kdms.ddl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import kdms.rules.Rules;
import kdms.rules.RulesLoader;

class DefaultTranslatorTest {

    private final Rules rules = RulesLoader.load(null);

    private DefaultTranslator.Result t(String def, String type) {
        return DefaultTranslator.translate(def, type, rules, "dbo",
                (s, n) -> (s + "." + n).equalsIgnoreCase("dbo.seq_doc_no") ? "\"dbo\".\"seq_doc_no\"" : null);
    }

    @Test
    void 상수() {
        assertThat(t("((0))", "integer").expr()).isEqualTo("0");
        assertThat(t("((1.50))", "numeric(9,2)").expr()).isEqualTo("1.50");
        assertThat(t("(-(1))", "integer").expr()).isEqualTo("-1");
        assertThat(t("((0))", "boolean").expr()).isEqualTo("false");
        assertThat(t("((1))", "boolean").expr()).isEqualTo("true");
        assertThat(t("('Y')", "char(1)").expr()).isEqualTo("'Y'");
        assertThat(t("(N'한글''s')", "varchar(10)").expr()).isEqualTo("'한글''s'");
        assertThat(t("('19000101')", "timestamp(3)").expr()).isEqualTo("'19000101'");
        assertThat(t("(NULL)", "integer").expr()).isNull();
        assertThat(t("(NULL)", "integer").error()).isNull();
    }

    @Test
    void 시퀀스와_함수() {
        assertThat(t("(NEXT VALUE FOR [dbo].[seq_doc_no])", "bigint").expr()).isEqualTo("nextval('\"dbo\".\"seq_doc_no\"'::regclass)");
        assertThat(t("(NEXT VALUE FOR seq_doc_no)", "bigint").expr()).isEqualTo("nextval('\"dbo\".\"seq_doc_no\"'::regclass)");
        assertThat(t("(NEXT VALUE FOR [dbo].[other])", "bigint").error()).contains("이관 대상에 없다");
        assertThat(t("(getdate())", "timestamp(3)").expr()).isEqualTo("LOCALTIMESTAMP");
        assertThat(t("(getdate())", "timestamp(3)").warn()).contains("시간대");
        assertThat(t("(CURRENT_TIMESTAMP)", "timestamp(3)").expr()).isEqualTo("LOCALTIMESTAMP");
        assertThat(t("(newid())", "uuid").expr()).isEqualTo("gen_random_uuid()");
        assertThat(t("(suser_sname())", "varchar(128)").expr()).isEqualTo("session_user");
    }

    @Test
    void 못_옮기는_식은_실패() {
        assertThat(t("(dateadd(day,(1),getdate()))", "timestamp(3)").error()).contains("자동으로 옮기지 않는 식");
        assertThat(t("((1)+(2))", "integer").error()).isNotNull();
        assertThat(t("('a'+'b')", "varchar(2)").error()).isNotNull();
        assertThat(t("(my_func())", "integer").error()).contains("defaults.functions");
        assertThat(t("((2))", "boolean").error()).contains("0/1");
    }

    @Test
    void 괄호_벗기기() {
        assertThat(DefaultTranslator.stripParens("((0))")).isEqualTo("0");
        assertThat(DefaultTranslator.stripParens("(1)+(2)")).isEqualTo("(1)+(2)");
        assertThat(DefaultTranslator.stripParens("(')(')")).isEqualTo("')('");
        assertThat(DefaultTranslator.identifierParts("[dbo].[a]]b]")).containsExactly("dbo", "a]b");
    }
}
