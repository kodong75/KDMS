package kdms.rules;

import java.util.List;
import java.util.Map;

/**
 * 검사가 끝난 변환 규칙(kdms-rules.yml). 적용은 2단계(스키마 변환)에서 한다.
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
        String computedColumns,
        Map<String, Object> tables) {

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
}
