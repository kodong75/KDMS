package kdms.ddl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kdms.ddl.SchemaPlan.ColumnPlan;
import kdms.ddl.SchemaPlan.TablePlan;

/**
 * {@link SchemaPlan} → 대상 PG DDL. 세 단계로 나눈다(plan.md §4.3·§4.5).
 * <ul>
 *   <li>{@link Phase#PRE_LOAD}: 스키마·콜레이션·시퀀스·테이블(컬럼, NOT NULL, 기본값, IDENTITY, CHECK, 계산 컬럼, PK)</li>
 *   <li>{@link Phase#POST_LOAD}: 전체 적재 뒤 UNIQUE·보조 인덱스</li>
 *   <li>{@link Phase#CUTOVER}: 전환 때 FK. IDENTITY·SEQUENCE setval 은 전환 시점 원천 값으로 5단계에서 만든다</li>
 * </ul>
 */
public final class DdlWriter {

    public enum Phase {
        PRE_LOAD("10_pre_load.sql", "적재 전: 스키마·시퀀스·테이블·PK"),
        POST_LOAD("20_post_load.sql", "적재 뒤: UNIQUE·보조 인덱스"),
        CUTOVER("30_cutover.sql", "전환 때: FK (setval 은 kdms cutover 가 전환 시점 값으로)");

        public final String fileName;
        public final String title;

        Phase(String fileName, String title) {
            this.fileName = fileName;
            this.title = title;
        }
    }

    private DdlWriter() {
    }

    /** 단계별 SQL. 헤더 주석 + 문장마다 세미콜론·줄바꿈 */
    public static String write(SchemaPlan plan, Phase phase) {
        StringBuilder b = new StringBuilder();
        b.append("-- KDMS 스키마 변환 · ").append(phase.title).append('\n');
        b.append("-- 원천 DB ").append(plan.sourceDb()).append(" · kdms plan 이 만든 파일(직접 고치지 말고 규칙 파일을 고쳐 다시 만든다)\n\n");
        for (String stmt : statements(plan, phase)) {
            b.append(stmt).append(";\n");
        }
        return b.toString();
    }

    /** 단계별 문장(세미콜론 없음). 적용기는 이것을 한 트랜잭션으로 실행한다 */
    public static List<String> statements(SchemaPlan plan, Phase phase) {
        List<String> out = new ArrayList<>();
        switch (phase) {
            case PRE_LOAD -> {
                Set<String> schemas = new LinkedHashSet<>();
                plan.sequences().forEach(s -> schemas.add(s.tgtSchema()));
                plan.tables().forEach(t -> schemas.add(t.tgtSchema()));
                for (String s : schemas) {
                    out.add("CREATE SCHEMA IF NOT EXISTS " + Names.quote(s));
                }
                for (String s : plan.collations()) {
                    // 대소문자 무시·악센트 구분(원천 *_CI_AS 와 같은 비교)
                    out.add("CREATE COLLATION IF NOT EXISTS " + Names.quote(s) + "." + Names.quote(SchemaPlan.CI_COLLATION)
                            + " (provider = icu, locale = 'und-u-ks-level2', deterministic = false)");
                }
                plan.sequences().forEach(s -> out.add(s.ddl()));
                for (TablePlan t : plan.tables()) {
                    out.add(createTable(t));
                    for (ColumnPlan c : t.columns()) {
                        String comment = comment(c);
                        if (comment != null) {
                            out.add("COMMENT ON COLUMN " + t.tgtQualified() + "." + Names.quote(c.tgtName()) + " IS " + Names.literal(comment));
                        }
                    }
                }
            }
            case POST_LOAD -> plan.tables().forEach(t -> t.postLoad().forEach(i -> out.addAll(List.of(i.ddl().split(";\n")))));
            case CUTOVER -> plan.tables().forEach(t -> t.foreignKeys().forEach(f -> out.add(f.ddl())));
        }
        return out;
    }

    static String createTable(TablePlan t) {
        List<String> lines = new ArrayList<>();
        for (ColumnPlan c : t.columns()) {
            StringBuilder b = new StringBuilder("    ").append(Names.quote(c.tgtName())).append(' ').append(c.tgtType());
            if (c.collate() != null) {
                b.append(" COLLATE ").append(c.collate());
            }
            if (c.generated() != null) {
                b.append(" GENERATED ALWAYS AS (").append(c.generated()).append(") STORED");
            }
            if (c.identity() != null) {
                b.append(' ').append(c.identity());
            }
            if (c.defaultExpr() != null) {
                b.append(" DEFAULT ").append(c.defaultExpr());
            }
            if (!c.nullable()) {
                b.append(" NOT NULL");
            }
            if (c.check() != null) {
                b.append(" CHECK (").append(c.check()).append(')');
            }
            lines.add(b.toString());
        }
        if (t.primaryKey() != null) {
            lines.add("    CONSTRAINT " + Names.quote(t.primaryKey().name()) + " PRIMARY KEY ("
                    + String.join(", ", t.primaryKey().columns().stream().map(Names::quote).toList()) + ")");
        }
        return "CREATE TABLE " + t.tgtQualified() + " (\n" + String.join(",\n", lines) + "\n)";
    }

    /** 원천 계산 컬럼 식·별칭 타입 등 대상에서 사라지는 정보를 남긴다 */
    static String comment(ColumnPlan c) {
        if (c.source().computed() != null) {
            return "MS-SQL 계산 컬럼" + (c.generated() == null ? "(값만 이관)" : "") + " 원문: " + c.source().computed().definition();
        }
        if (c.source().aliasType() != null) {
            return "MS-SQL 별칭 타입 " + c.source().aliasType();
        }
        return null;
    }

    /** 단계 → 파일 내용 */
    public static Map<Phase, String> all(SchemaPlan plan) {
        Map<Phase, String> m = new java.util.EnumMap<>(Phase.class);
        for (Phase p : Phase.values()) {
            m.put(p, write(plan, p));
        }
        return m;
    }
}
