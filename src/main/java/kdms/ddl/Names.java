package kdms.ddl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** PG 식별자: 따옴표, 63바이트 제한, 스키마 안 이름 충돌. */
public final class Names {

    /** PG NAMEDATALEN - 1 */
    public static final int MAX_BYTES = 63;

    private Names() {
    }

    /** 항상 큰따옴표로 감싼다(예약어·대소문자 문제를 없앤다) */
    public static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** PG 문자열 상수 */
    public static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    public static int bytes(String name) {
        return name.getBytes(StandardCharsets.UTF_8).length;
    }

    /** identifiers.case 규칙 */
    public static String applyCase(String name, String caseRule) {
        return "lower".equals(caseRule) ? name.toLowerCase(Locale.ROOT) : name;
    }

    /** 63바이트가 넘으면 앞부분 + "_" + 해시 8자리로 줄인다(만든 이름 전용. 원천 테이블·컬럼 이름은 오류로 처리) */
    public static String fit(String name) {
        if (bytes(name) <= MAX_BYTES) {
            return name;
        }
        String suffix = "_" + hash8(name);
        StringBuilder b = new StringBuilder();
        for (int cp : name.codePoints().toArray()) {
            String ch = new String(Character.toChars(cp));
            if (bytes(b + ch) + suffix.length() > MAX_BYTES) {
                break;
            }
            b.append(ch);
        }
        return b + suffix;
    }

    private static String hash8(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 한 스키마 안의 relation 이름(테이블·시퀀스·인덱스·PK/UNIQUE 제약)은 겹치면 안 된다.
     * MS-SQL 은 인덱스 이름이 테이블마다 따로라 같은 이름이 여러 테이블에 있을 수 있다.
     */
    public static final class Namespace {
        private final Set<String> used = new HashSet<>();

        /** 이미 정해진 이름(테이블·시퀀스). 겹치면 false */
        public boolean reserve(String schema, String name) {
            return used.add(schema + "\u0000" + name);
        }

        /**
         * 인덱스·제약 이름. 겹치면 "이름_테이블", 그래도 겹치면 숫자를 붙인다.
         *
         * @return 실제로 쓸 이름
         */
        public String claim(String schema, String wanted, String table) {
            String n = fit(wanted);
            if (reserve(schema, n)) {
                return n;
            }
            n = fit(wanted + "_" + table);
            for (int i = 2; !reserve(schema, n); i++) {
                n = fit(wanted + "_" + table + "_" + i);
            }
            return n;
        }
    }
}
