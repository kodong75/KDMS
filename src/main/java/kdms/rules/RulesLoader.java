package kdms.rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import kdms.config.ConfigException;

/**
 * jar 안의 기본 규칙(kdms-rules.yml) 위에 작업별 규칙 파일을 덮어써서 읽고, 키·값을 검사한다.
 * 알 수 없는 키나 허용되지 않은 값은 실행 전에 멈춘다(오타로 규칙이 조용히 무시되지 않게).
 */
public final class RulesLoader {

    public static final String DEFAULT_RESOURCE = "kdms-rules.yml";

    private static final Set<String> TOP_KEYS = Set.of("version", "types", "identity", "sequence", "text", "collation",
            "sentinel_dates", "identifiers", "computed_columns", "tables");
    private static final Set<String> TYPE_KEYS = Set.of("to", "max", "max_precision", "round", "check", "warn");

    private RulesLoader() {
    }

    /** @param overrideFile 작업별 규칙 파일. null 이면 기본 규칙만. */
    public static Rules load(Path overrideFile) {
        Map<String, Object> merged = readDefault();
        if (overrideFile != null) {
            if (!Files.isRegularFile(overrideFile)) {
                throw new ConfigException("규칙 파일이 없습니다: " + overrideFile);
            }
            try (Reader r = Files.newBufferedReader(overrideFile, StandardCharsets.UTF_8)) {
                Object o = yaml().load(r);
                if (o != null) {
                    merged = deepMerge(merged, asMap(o, "(규칙 파일 최상위)"));
                }
            } catch (IOException e) {
                throw new ConfigException("규칙 파일을 읽지 못했습니다: " + overrideFile + " (" + e.getMessage() + ")");
            } catch (org.yaml.snakeyaml.error.YAMLException e) {
                throw new ConfigException("규칙 파일 YAML 오류: " + overrideFile + "\n" + e.getMessage());
            }
        }
        return bind(merged);
    }

    static Map<String, Object> readDefault() {
        try (InputStream in = RulesLoader.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("jar 안에 " + DEFAULT_RESOURCE + " 가 없다");
            }
            return asMap(yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8)), "(기본 규칙)");
        } catch (IOException e) {
            throw new IllegalStateException(DEFAULT_RESOURCE + " 를 읽지 못했다", e);
        }
    }

    static Rules bind(Map<String, Object> m) {
        keys(m, TOP_KEYS, "(최상위)");
        int version = intVal(m.get("version"), "version");
        if (version != 1) {
            throw new ConfigException("version: 지원하는 규칙 파일 버전은 1 이다 (값: " + version + ")");
        }

        Map<String, Rules.TypeRule> types = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : section(m, "types").entrySet()) {
            String where = "types." + e.getKey();
            Map<String, Object> t = asMap(e.getValue(), where);
            keys(t, TYPE_KEYS, where);
            String to = str(t.get("to"), where + ".to");
            if (to == null || to.isBlank()) {
                throw new ConfigException(where + ".to: 대상 타입이 비어 있다");
            }
            Integer maxP = t.get("max_precision") == null ? null : intVal(t.get("max_precision"), where + ".max_precision");
            String round = oneOf(t.get("round"), where + ".round", null, "half_up", "truncate");
            if (maxP != null && round == null) {
                throw new ConfigException(where + ": max_precision 을 쓰면 round(half_up | truncate)도 정한다");
            }
            types.put(e.getKey().toLowerCase(), new Rules.TypeRule(to, str(t.get("max"), where + ".max"), maxP, round,
                    str(t.get("check"), where + ".check"), str(t.get("warn"), where + ".warn")));
        }

        Map<String, Object> identity = section(m, "identity");
        keys(identity, Set.of("to", "setval"), "identity");
        Map<String, Object> sequence = section(m, "sequence");
        keys(sequence, Set.of("setval"), "sequence");
        Map<String, Object> text = section(m, "text");
        keys(text, Set.of("trailing_space", "nul_char", "nul_replacement", "case"), "text");
        Map<String, Object> collation = section(m, "collation");
        keys(collation, Set.of("target", "ci_unique"), "collation");
        Map<String, Object> sentinel = section(m, "sentinel_dates");
        keys(sentinel, Set.of("values", "action"), "sentinel_dates");
        Map<String, Object> ident = section(m, "identifiers");
        keys(ident, Set.of("case", "map"), "identifiers");
        Map<String, Object> computed = section(m, "computed_columns");
        keys(computed, Set.of("action"), "computed_columns");

        Map<String, String> identMap = new LinkedHashMap<>();
        Object mapNode = ident.get("map");
        if (mapNode != null) {
            asMap(mapNode, "identifiers.map").forEach((k, v) -> identMap.put(k, str(v, "identifiers.map." + k)));
        }
        List<String> sentinelValues = new ArrayList<>();
        Object sv = sentinel.get("values");
        if (sv != null) {
            if (!(sv instanceof List<?> list)) {
                throw new ConfigException("sentinel_dates.values: 목록이어야 한다");
            }
            list.forEach(o -> sentinelValues.add(String.valueOf(o)));
        }

        return new Rules(
                version,
                Map.copyOf(types),
                str(identity.get("to"), "identity.to"),
                oneOf(identity.get("setval"), "identity.setval", "from_ident_current", "from_ident_current", "from_max", "none"),
                oneOf(sequence.get("setval"), "sequence.setval", "from_current_value", "from_current_value", "none"),
                new Rules.TextRule(
                        oneOf(text.get("trailing_space"), "text.trailing_space", "keep", "keep", "rtrim"),
                        oneOf(text.get("nul_char"), "text.nul_char", "fail", "fail", "strip", "replace"),
                        str(text.get("nul_replacement"), "text.nul_replacement"),
                        oneOf(text.get("case"), "text.case", "keep", "keep", "upper", "lower")),
                oneOf(collation.get("target"), "collation.target", "C", "C", "ko-KR-x-icu", "ci"),
                oneOf(collation.get("ci_unique"), "collation.ci_unique", "lower_index", "none", "lower_index", "ci_collation"),
                List.copyOf(sentinelValues),
                oneOf(sentinel.get("action"), "sentinel_dates.action", "keep", "keep", "null", "infinity"),
                oneOf(ident.get("case"), "identifiers.case", "lower", "lower", "keep"),
                Map.copyOf(identMap),
                oneOf(computed.get("action"), "computed_columns.action", "generated_stored", "generated_stored", "value_with_warning"),
                m.get("tables") == null ? Map.of() : Map.copyOf(asMap(m.get("tables"), "tables")));
    }

    /** 맵은 키별로 재귀 병합, 그 밖(값·목록)은 덮어쓴다. */
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> over) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : over.entrySet()) {
            Object b = out.get(e.getKey());
            if (b instanceof Map && e.getValue() instanceof Map) {
                out.put(e.getKey(), deepMerge(asMap(b, e.getKey()), asMap(e.getValue(), e.getKey())));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static Yaml yaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    private static Map<String, Object> section(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? Map.of() : asMap(v, key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v, String where) {
        if (!(v instanceof Map)) {
            throw new ConfigException(where + ": 키-값 묶음이어야 한다");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        ((Map<Object, Object>) v).forEach((k, val) -> out.put(String.valueOf(k), val));
        return out;
    }

    private static void keys(Map<String, Object> m, Set<String> allowed, String where) {
        for (String k : m.keySet()) {
            if (!allowed.contains(k)) {
                throw new ConfigException(where + ": 알 수 없는 키 '" + k + "' (허용: " + String.join(", ", new TreeSet<>(allowed)) + ")");
            }
        }
    }

    private static String str(Object v, String where) {
        if (v == null) {
            return null;
        }
        if (v instanceof Map || v instanceof List) {
            throw new ConfigException(where + ": 문자열이어야 한다");
        }
        return String.valueOf(v);
    }

    private static int intVal(Object v, String where) {
        if (v instanceof Integer i) {
            return i;
        }
        throw new ConfigException(where + ": 정수여야 한다 (값: " + v + ")");
    }

    private static String oneOf(Object v, String where, String def, String... allowed) {
        String s = str(v, where);
        if (s == null) {
            return def;
        }
        for (String a : allowed) {
            if (a.equals(s)) {
                return s;
            }
        }
        throw new ConfigException(where + ": 허용되지 않은 값 '" + s + "' (허용: " + String.join(" | ", allowed) + ")");
    }
}
