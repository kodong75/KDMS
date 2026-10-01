package kdms.ddl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kdms.rules.Rules;

/**
 * MS-SQL 기본값 원문(sys.default_constraints.definition) → PG 기본값 식.
 * 자동으로 옮기는 것: NULL, 숫자·문자열 상수, NEXT VALUE FOR 시퀀스, 규칙 파일 defaults.functions 의 인자 없는 함수.
 * 그 밖의 식은 옮기지 않고 실패로 돌려준다(규칙 파일에서 컬럼별 default 로 직접 지정).
 */
public final class DefaultTranslator {

    /**
     * @param expr  대상 식. null 이면 기본값 없음(원천이 NULL)
     * @param warn  보고서 경고, 없으면 null
     * @param error 옮기지 못한 이유. null 이 아니면 실패
     */
    public record Result(String expr, String warn, String error) {
        static Result ok(String expr) {
            return new Result(expr, null, null);
        }

        static Result fail(String error) {
            return new Result(null, null, error);
        }
    }

    private static final Pattern NUMBER = Pattern.compile("[0-9]+(\\.[0-9]*)?|\\.[0-9]+");
    private static final Pattern STRING = Pattern.compile("(?s)N?'(.*)'");
    private static final Pattern NEXT_VALUE = Pattern.compile("(?i)NEXT\\s+VALUE\\s+FOR\\s+(.+)");
    private static final Pattern FUNCTION = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*\\)");
    private static final Pattern BARE = Pattern.compile("(?i)current_timestamp|current_user|session_user|system_user|user");

    private DefaultTranslator() {
    }

    /**
     * @param definition    원천 원문
     * @param tgtType       대상 컬럼 타입(boolean 이면 0/1 → false/true)
     * @param defaultSchema 시퀀스 이름에 스키마가 없을 때 쓸 원천 스키마
     * @param sequenceName  (원천 스키마, 원천 시퀀스) → 대상 "스키마"."이름", 없는 시퀀스면 null
     */
    public static Result translate(String definition, String tgtType, Rules rules, String defaultSchema,
                                   BiFunction<String, String, String> sequenceName) {
        String d = stripParens(definition.strip());
        if (d.equalsIgnoreCase("NULL")) {
            return Result.ok(null);
        }
        String num = number(d);
        if (num != null) {
            if ("boolean".equals(tgtType)) {
                return switch (num) {
                    case "0" -> Result.ok("false");
                    case "1" -> Result.ok("true");
                    default -> Result.fail("bit 기본값이 0/1 이 아니다: " + definition);
                };
            }
            return Result.ok(num);
        }
        Matcher m = STRING.matcher(d);
        if (m.matches() && !m.group(1).replace("''", "").contains("'")) {
            String v = m.group(1).replace("''", "'");
            if ("boolean".equals(tgtType) && (v.equals("0") || v.equals("1"))) {
                return Result.ok(v.equals("1") ? "true" : "false");
            }
            return Result.ok(Names.literal(v));
        }
        m = NEXT_VALUE.matcher(d);
        if (m.matches()) {
            List<String> parts = identifierParts(m.group(1).strip());
            if (parts == null || parts.isEmpty() || parts.size() > 2) {
                return Result.fail("시퀀스 이름을 읽지 못했다: " + definition);
            }
            String schema = parts.size() == 2 ? parts.get(0) : defaultSchema;
            String target = sequenceName.apply(schema, parts.get(parts.size() - 1));
            if (target == null) {
                return Result.fail("기본값이 가리키는 시퀀스가 이관 대상에 없다: " + definition);
            }
            return Result.ok("nextval(" + Names.literal(target) + "::regclass)");
        }
        String fn = null;
        m = FUNCTION.matcher(d);
        if (m.matches()) {
            fn = m.group(1);
        } else if (BARE.matcher(d).matches()) {
            fn = d;
        }
        if (fn != null) {
            Rules.FunctionRule f = rules.defaultFunctions().get(fn.toLowerCase(Locale.ROOT));
            if (f == null) {
                return Result.fail("규칙 defaults.functions 에 없는 함수: " + definition);
            }
            return new Result(f.to(), f.warn(), null);
        }
        return Result.fail("자동으로 옮기지 않는 식: " + definition);
    }

    /** "-(1)", "((0))", "1.5" 같은 숫자 상수. 아니면 null */
    static String number(String d) {
        String s = stripParens(d);
        if (s.startsWith("-") || s.startsWith("+")) {
            String rest = number(s.substring(1).strip());
            if (rest == null || rest.startsWith("-")) {
                return null;
            }
            return s.charAt(0) == '-' ? (rest.matches("0*(\\.0*)?") ? rest : "-" + rest) : rest;
        }
        return NUMBER.matcher(s).matches() ? s : null;
    }

    /** 바깥 괄호가 식 전체를 감쌀 때만 벗긴다("(a)+(b)" 는 그대로) */
    static String stripParens(String s) {
        String cur = s.strip();
        while (cur.startsWith("(") && closingParen(cur) == cur.length() - 1) {
            cur = cur.substring(1, cur.length() - 1).strip();
        }
        return cur;
    }

    /** 0 번 '(' 의 짝 위치. 문자열·[식별자] 안은 건너뛴다 */
    private static int closingParen(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'') {
                i = s.indexOf('\'', i + 1);
                while (i > 0 && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i = s.indexOf('\'', i + 2);
                }
                if (i < 0) {
                    return -1;
                }
            } else if (ch == '[') {
                i = s.indexOf(']', i + 1);
                if (i < 0) {
                    return -1;
                }
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** [dbo].[seq], dbo.seq, "dbo"."seq" → [dbo, seq]. 읽지 못하면 null */
    static List<String> identifierParts(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '[') {
                StringBuilder b = new StringBuilder();
                int j = i + 1;
                while (j < s.length()) {
                    if (s.charAt(j) == ']') {
                        if (j + 1 < s.length() && s.charAt(j + 1) == ']') {
                            b.append(']');
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    b.append(s.charAt(j++));
                }
                if (j >= s.length()) {
                    return null;
                }
                out.add(b.toString());
                i = j + 1;
            } else if (ch == '"') {
                int j = s.indexOf('"', i + 1);
                if (j < 0) {
                    return null;
                }
                out.add(s.substring(i + 1, j));
                i = j + 1;
            } else {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '#' || s.charAt(j) == '@' || s.charAt(j) == '$')) {
                    j++;
                }
                if (j == i) {
                    return null;
                }
                out.add(s.substring(i, j));
                i = j;
            }
            if (i < s.length()) {
                if (s.charAt(i) != '.') {
                    return null;
                }
                i++;
            }
        }
        return out;
    }
}
