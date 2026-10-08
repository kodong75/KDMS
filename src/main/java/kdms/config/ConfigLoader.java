package kdms.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * config/kdms.yml 을 읽어 {@link KdmsConfig} 로 만든다.
 * YAML 을 먼저 해석하고 그 다음 문자열 값 안의 {@code ${…}} 를 채운다(비밀번호의 특수문자가 YAML 문법을 깨지 않게).
 */
public final class ConfigLoader {

    private static final Set<String> TOP_KEYS = Set.of("job_name", "source", "target", "load", "sync", "tables", "rules", "web");
    private static final Set<String> ENDPOINT_KEYS = Set.of("host", "port", "database", "user", "password", "properties");

    /** 자리표시 ${이름} / ${이름:기본값} 의 이름 */
    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)");

    private final EnvResolver env;

    public ConfigLoader(EnvResolver env) {
        this.env = env;
    }

    public KdmsConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("설정 파일이 없습니다: " + file
                    + " (config/kdms.example.yml 을 config/kdms.yml 로 복사해 고친다)");
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
            return bind(map(root, "(최상위)"));
        } catch (IOException e) {
            throw new ConfigException("설정 파일을 읽지 못했습니다: " + file + " (" + e.getMessage() + ")");
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new ConfigException("설정 파일 YAML 오류: " + file + "\n" + e.getMessage());
        }
    }

    KdmsConfig bind(Map<String, Object> root) {
        checkKeys(root, TOP_KEYS, "(최상위)");
        Map<String, Object> load = optMap(root.get("load"), "load");
        Map<String, Object> sync = optMap(root.get("sync"), "sync");
        Map<String, Object> tables = optMap(root.get("tables"), "tables");
        Map<String, Object> web = optMap(root.get("web"), "web");
        checkKeys(load, Set.of("table_parallelism", "chunks_per_table", "isolation"), "load");
        checkKeys(sync, Set.of("batch_size", "poll_ms", "status_seconds"), "sync");
        checkKeys(tables, Set.of("include", "exclude"), "tables");
        checkKeys(web, Set.of("address", "port"), "web");

        return new KdmsConfig(
                str(root.get("job_name"), "job_name", "kdms_mock"),
                endpoint(root.get("source"), "source", 1433),
                endpoint(root.get("target"), "target", 5432),
                new KdmsConfig.LoadSettings(
                        positiveInt(load.get("table_parallelism"), "load.table_parallelism", 4),
                        positiveInt(load.get("chunks_per_table"), "load.chunks_per_table", 2),
                        oneOf(load.get("isolation"), "load.isolation", "snapshot", "snapshot", "read_committed")),
                new KdmsConfig.SyncSettings(
                        positiveInt(sync.get("batch_size"), "sync.batch_size", 1000),
                        positiveInt(sync.get("poll_ms"), "sync.poll_ms", 500),
                        positiveInt(sync.get("status_seconds"), "sync.status_seconds", 10)),
                new KdmsConfig.TableSelection(
                        strList(tables.get("include"), "tables.include", List.of("dbo.*")),
                        strList(tables.get("exclude"), "tables.exclude", List.of())),
                str(root.get("rules"), "rules", ""),
                new KdmsConfig.WebSettings(
                        str(web.get("address"), "web.address", "127.0.0.1"),
                        positiveInt(web.get("port"), "web.port", 8080)));
    }

    private KdmsConfig.Endpoint endpoint(Object node, String where, int defaultPort) {
        if (node == null) {
            throw new ConfigException(where + ": 항목이 없습니다");
        }
        Map<String, Object> m = map(node, where);
        checkKeys(m, ENDPOINT_KEYS, where);
        Map<String, String> props = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : optMap(m.get("properties"), where + ".properties").entrySet()) {
            props.put(e.getKey(), str(e.getValue(), where + ".properties." + e.getKey(), ""));
        }
        return new KdmsConfig.Endpoint(
                required(m.get("host"), where + ".host"),
                positiveInt(m.get("port"), where + ".port", defaultPort),
                required(m.get("database"), where + ".database"),
                required(m.get("user"), where + ".user"),
                password(m.get("password"), where + ".password"),
                Map.copyOf(props));
    }

    /**
     * 비밀번호가 비어 있으면 접속을 시도하기 전에 설정 오류로 멈춘다(빈 값으로 붙으면 "Login failed" 만 보여 원인을 찾기 어렵다).
     * 메시지에는 값 대신 채울 곳(환경 변수 이름)만 쓴다.
     */
    private String password(Object v, String where) {
        String s = str(v, where, "");
        if (s.isEmpty()) {
            Matcher m = v == null ? null : PLACEHOLDER_NAME.matcher(String.valueOf(v));
            String fill = m != null && m.find() ? ".env(또는 환경 변수)의 " + m.group(1) + "=" : "설정 파일의 " + where;
            throw new ConfigException(where + ": 비밀번호가 비어 있습니다. " + fill + " 에 비밀번호를 넣는다");
        }
        return s;
    }

    private String oneOf(Object v, String where, String def, String... allowed) {
        String s = str(v, where, null);
        if (s == null || s.isBlank()) {
            return def;
        }
        for (String a : allowed) {
            if (a.equals(s.strip())) {
                return a;
            }
        }
        throw new ConfigException(where + ": " + String.join(" | ", allowed) + " 중 하나여야 합니다 (값: " + s + ")");
    }

    private String required(Object v, String where) {
        String s = str(v, where, null);
        if (s == null || s.isBlank()) {
            throw new ConfigException(where + ": 값이 비어 있습니다");
        }
        return s;
    }

    private String str(Object v, String where, String def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Map || v instanceof List) {
            throw new ConfigException(where + ": 문자열이어야 합니다");
        }
        return env.resolve(String.valueOf(v), where);
    }

    private int positiveInt(Object v, String where, int def) {
        String s = str(v, where, null);
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            int n = Integer.parseInt(s.strip());
            if (n <= 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new ConfigException(where + ": 양의 정수여야 합니다 (값: " + s + ")");
        }
    }

    private List<String> strList(Object v, String where, List<String> def) {
        if (v == null) {
            return def;
        }
        if (!(v instanceof List<?> list)) {
            throw new ConfigException(where + ": 목록이어야 합니다");
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(str(list.get(i), where + "[" + i + "]", ""));
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v, String where) {
        if (!(v instanceof Map)) {
            throw new ConfigException(where + ": 키-값 묶음이어야 합니다");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        ((Map<Object, Object>) v).forEach((k, val) -> out.put(String.valueOf(k), val));
        return out;
    }

    private static Map<String, Object> optMap(Object v, String where) {
        return v == null ? Map.of() : map(v, where);
    }

    private static void checkKeys(Map<String, Object> m, Set<String> allowed, String where) {
        for (String k : m.keySet()) {
            if (!allowed.contains(k)) {
                throw new ConfigException(where + ": 알 수 없는 키 '" + k + "' (허용: " + String.join(", ", allowed.stream().sorted().toList()) + ")");
            }
        }
    }
}
