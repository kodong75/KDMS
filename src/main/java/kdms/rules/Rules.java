package kdms.rules;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 검사가 끝난 변환 규칙(kdms-rules.yml). 적용은 {@code kdms.ddl.SchemaPlanner}.
 *
 * @param defaultFunctions 원천 기본값 함수(소문자, 인자 없음) → 대상 식
 * @param identifierMap    "schema.table"(소문자) → 대상 테이블 이름
 * @param schemaMap        원천 스키마(소문자) → 대상 스키마. 없으면 규칙 identifiers.case 대로
 * @param tables           "schema.table"(소문자) → 테이블·컬럼 단위 덮어쓰기
 */
public record Rules(
        int version,
        Map<String, TypeRule> types,
        String identityTo,
        String identitySetval,
        String sequenceSetval,
        TextRule text,
        String collationTarget,
        String ciUnique,
        List<String> sentinelValues,
        String sentinelAction,
        String identifierCase,
        Map<String, String> identifierMap,
        Map<String, String> schemaMap,
        String computedColumns,
        Map<String, FunctionRule> defaultFunctions,
        Map<String, TableRule> tables) {

    /**
     * @param to           대상 타입 템플릿({n}, {p}, {s})
     * @param max          (max) 길이일 때 대상 타입, 없으면 null
     * @param maxPrecision {p} 상한, 없으면 null
     * @param round        상한으로 줄일 때 반올림 방식(half_up), 없으면 null
     * @param check        대상 CHECK 조건(예: BETWEEN 0 AND 255), 없으면 null
     * @param warn         kdms plan 보고서에 낼 경고, 없으면 null
     */
    public record TypeRule(String to, String max, Integer maxPrecision, String round, String check, String warn) {
    }

    public record TextRule(String trailingSpace, String nulChar, String nulReplacement, String caseRule) {
    }

    /** 원천 기본값 함수 → 대상 식. warn 은 보고서 경고(없으면 null). */
    public record FunctionRule(String to, String warn) {
    }

    /**
     * 테이블 단위 덮어쓰기.
     *
     * @param exclude 이관에서 뺀다
     * @param columns 컬럼 이름(소문자) → 컬럼 단위 덮어쓰기
     */
    public record TableRule(boolean exclude, Map<String, ColumnRule> columns) {
    }

    /**
     * 컬럼 단위 덮어쓰기. null 이면 전체 규칙을 따른다.
     *
     * @param name          대상 컬럼 이름
     * @param type          대상 타입(타입 규칙 대신 그대로 쓴다)
     * @param trailingSpace keep | rtrim
     * @param caseRule      keep | upper | lower
     * @param nulChar       fail | strip | replace
     * @param defaultExpr   대상 기본값 식. "none" 이면 기본값을 두지 않는다
     * @param generated     계산 컬럼의 대상 식(PG). GENERATED ALWAYS AS (식) STORED
     * @param computed      generated_stored | value_with_warning
     */
    public record ColumnRule(String name, String type, String trailingSpace, String caseRule, String nulChar,
                             String defaultExpr, String generated, String computed) {
    }

    /** 규칙 파일의 tables 키 형식("schema.table", 소문자). */
    public static String tableKey(String schema, String table) {
        return (schema + "." + table).toLowerCase(Locale.ROOT);
    }

    public TableRule table(String schema, String table) {
        return tables.get(tableKey(schema, table));
    }

    /** 컬럼 덮어쓰기. 없으면 null. */
    public ColumnRule column(String schema, String table, String column) {
        TableRule t = table(schema, table);
        return t == null ? null : t.columns().get(column.toLowerCase(Locale.ROOT));
    }
}
