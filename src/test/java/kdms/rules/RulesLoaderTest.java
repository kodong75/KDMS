package kdms.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import kdms.config.ConfigException;

class RulesLoaderTest {

    @Test
    void 기본_규칙은_KIS_정규화_매핑과_같다() {
        Rules r = RulesLoader.load(null);
        assertThat(r.types().get("money").to()).isEqualTo("numeric(19,4)");
        assertThat(r.types().get("smallmoney").to()).isEqualTo("numeric(10,4)");
        assertThat(r.types().get("bit").to()).isEqualTo("boolean");
        assertThat(r.types().get("datetime").to()).isEqualTo("timestamp(3)");
        assertThat(r.types().get("datetime2").maxPrecision()).isEqualTo(6);
        assertThat(r.types().get("datetime2").round()).isEqualTo("half_up");
        assertThat(r.types().get("uniqueidentifier").to()).isEqualTo("uuid");
        assertThat(r.types().get("rowversion").to()).isEqualTo("bytea");
        assertThat(r.types().get("nvarchar").max()).isEqualTo("text");
        assertThat(r.types().get("tinyint").check()).isEqualTo("BETWEEN 0 AND 255");
        assertThat(r.identitySetval()).isEqualTo("from_ident_current");
        assertThat(r.text().trailingSpace()).isEqualTo("keep");
        assertThat(r.text().nulChar()).isEqualTo("fail");
        assertThat(r.text().nulReplacement()).isEqualTo("�");
        assertThat(r.collationTarget()).isEqualTo("C");
        assertThat(r.ciUnique()).isEqualTo("lower_index");
        assertThat(r.sentinelValues()).containsExactly("1753-01-01", "1900-01-01", "9999-12-31");
        assertThat(r.identifierCase()).isEqualTo("lower");
    }

    @Test
    void 저장소의_작업별_규칙_예시도_읽힌다() {
        assertThat(RulesLoader.load(Path.of("config/kdms-rules.yml")).types()).hasSize(RulesLoader.load(null).types().size());
    }

    @Test
    void 작업별_파일은_적은_키만_덮어쓴다(@TempDir Path dir) throws IOException {
        Path f = write(dir, """
                version: 1
                types:
                  bit: { to: smallint, check: "IN (0, 1)" }
                text:
                  trailing_space: rtrim
                """);
        Rules r = RulesLoader.load(f);
        assertThat(r.types().get("bit").to()).isEqualTo("smallint");
        assertThat(r.types().get("money").to()).isEqualTo("numeric(19,4)"); // 나머지는 기본값
        assertThat(r.text().trailingSpace()).isEqualTo("rtrim");
        assertThat(r.text().nulChar()).isEqualTo("fail");
    }

    @Test
    void 허용되지_않은_값은_오류(@TempDir Path dir) throws IOException {
        Path f = write(dir, "text: { nul_char: ignore }\n");
        assertThatThrownBy(() -> RulesLoader.load(f)).isInstanceOf(ConfigException.class)
                .hasMessageContaining("text.nul_char").hasMessageContaining("fail | strip | replace");
    }

    @Test
    void 오타_키는_오류(@TempDir Path dir) throws IOException {
        Path f = write(dir, "colation: { target: C }\n");
        assertThatThrownBy(() -> RulesLoader.load(f)).hasMessageContaining("colation");
        Path g = write(dir, "types: { money: { too: numeric } }\n");
        assertThatThrownBy(() -> RulesLoader.load(g)).hasMessageContaining("types.money").hasMessageContaining("too");
    }

    @Test
    void 정밀도_상한에는_반올림_방식이_필요(@TempDir Path dir) throws IOException {
        Path f = write(dir, "types: { time: { to: 'time({p})', max_precision: 6, round: null } }\n");
        assertThatThrownBy(() -> RulesLoader.load(f)).hasMessageContaining("round");
    }

    @Test
    void 테이블_컬럼_덮어쓰기와_기본값_함수(@TempDir Path dir) throws IOException {
        Path f = write(dir, """
                identifiers: { schemas: { DBO: mock } }
                defaults:
                  functions:
                    my_fn: { to: "my_pg_fn()" }
                tables:
                  DBO.Rating:
                    columns:
                      Rating_CD: { trailing_space: rtrim, case: upper, default: none }
                """);
        Rules r = RulesLoader.load(f);
        assertThat(r.schemaMap()).containsEntry("dbo", "mock");
        assertThat(r.defaultFunctions().get("my_fn").to()).isEqualTo("my_pg_fn()");
        assertThat(r.defaultFunctions().get("getdate").to()).isEqualTo("LOCALTIMESTAMP"); // 기본 표는 그대로
        Rules.ColumnRule c = r.column("dbo", "rating", "rating_cd");
        assertThat(c.trailingSpace()).isEqualTo("rtrim");
        assertThat(c.caseRule()).isEqualTo("upper");
        assertThat(c.defaultExpr()).isEqualTo("none");
        assertThat(c.nulChar()).isNull(); // 지정 안 한 것은 전체 규칙을 따른다
    }

    @Test
    void 테이블_덮어쓰기_오타도_오류(@TempDir Path dir) throws IOException {
        Path f = write(dir, "tables: { dbo.t: { columns: { c: { trailing: rtrim } } } }\n");
        assertThatThrownBy(() -> RulesLoader.load(f)).hasMessageContaining("tables.dbo.t.columns.c").hasMessageContaining("trailing");
        Path g = write(dir, "tables: { t: { exclude: true } }\n");
        assertThatThrownBy(() -> RulesLoader.load(g)).hasMessageContaining("schema.table");
        Path h = write(dir, "tables: { dbo.t: { columns: { c: { computed: maybe } } } }\n");
        assertThatThrownBy(() -> RulesLoader.load(h)).hasMessageContaining("generated_stored | value_with_warning");
    }

    private static Path write(Path dir, String yaml) throws IOException {
        Path f = Files.createTempFile(dir, "rules", ".yml");
        Files.writeString(f, yaml);
        return f;
    }
}
