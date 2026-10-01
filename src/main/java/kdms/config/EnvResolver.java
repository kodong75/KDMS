package kdms.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 설정 값의 {@code ${NAME}} / {@code ${NAME:기본값}} 자리표시를 채운다.
 * 찾는 순서: 프로세스 환경 변수 → {@code .env} 파일. 비밀번호는 이 두 곳에만 둔다(plan.md §2).
 */
public final class EnvResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}");

    private final Function<String, String> processEnv;
    private final Map<String, String> dotEnv;

    public EnvResolver(Function<String, String> processEnv, Map<String, String> dotEnv) {
        this.processEnv = processEnv;
        this.dotEnv = Map.copyOf(dotEnv);
    }

    /** 실제 환경 변수 + 주어진 .env 파일(없으면 건너뜀). */
    public static EnvResolver system(Path dotEnvFile) {
        Map<String, String> fromFile = Map.of();
        if (dotEnvFile != null && Files.isRegularFile(dotEnvFile)) {
            try {
                fromFile = parseDotEnv(Files.readString(dotEnvFile, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new ConfigException(".env 파일을 읽지 못했습니다: " + dotEnvFile + " (" + e.getMessage() + ")");
            }
        }
        return new EnvResolver(System::getenv, fromFile);
    }

    public Optional<String> lookup(String name) {
        String v = processEnv.apply(name);
        if (v == null) {
            v = dotEnv.get(name);
        }
        return Optional.ofNullable(v);
    }

    /**
     * 문자열 안의 자리표시를 모두 채운다. 값이 없고 기본값도 없으면 예외.
     * 빈 값({@code KEY=})은 "있음"으로 본다. 빈 비밀번호 검사는 호출한 쪽이 한다.
     *
     * @param where 오류 메시지에 쓸 설정 키(예: {@code source.password}). 값은 메시지에 넣지 않는다.
     */
    public String resolve(String raw, String where) {
        if (raw == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String def = m.group(2);
            String value = lookup(name).orElse(def);
            if (value == null) {
                throw new ConfigException(where + ": 환경 변수 " + name + " 가 없습니다(.env 또는 환경 변수에 설정)");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * {@code KEY=VALUE} 줄만 읽는다. {@code #} 로 시작하는 줄과 빈 줄은 건너뛴다.
     * 값 양끝의 따옴표 한 쌍은 벗긴다. 값 뒤 주석은 지원하지 않는다(KIS .env 와 같은 규칙).
     */
    static Map<String, String> parseDotEnv(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.startsWith("export ")) {
                t = t.substring("export ".length()).strip();
            }
            int eq = t.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = t.substring(0, eq).strip();
            String value = t.substring(eq + 1).strip();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            map.put(key, value);
        }
        return map;
    }
}
