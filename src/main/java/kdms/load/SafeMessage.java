package kdms.load;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 오류 메시지에서 행 값을 지운다(plan.md R8: 로그·관리 테이블에 행 값을 남기지 않는다. PK 와 위치만).
 * PG 오류의 Detail(Failing row contains …)·Where(COPY …, column x: "값") 와 따옴표 안 값을 지우고 위치는 남긴다.
 */
public final class SafeMessage {

    private static final Pattern COPY_WHERE = Pattern.compile("COPY ([^,\\s]+), line (\\d+)(?:, column ([^:\\s]+))?");
    /** for type integer: "abc" / : "값" */
    private static final Pattern PG_VALUE = Pattern.compile(": \"(?:[^\"]|\"\")*\"");
    /** MS-SQL: converting the nvarchar value 'abc' to … */
    private static final Pattern SQ_VALUE = Pattern.compile("'(?:[^']|'')*'");

    private SafeMessage() {
    }

    public static String of(Throwable e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return of(m);
    }

    public static String of(String message) {
        String[] lines = message.split("\\R");
        String first = lines[0];
        first = PG_VALUE.matcher(first).replaceAll(": \"…\"");
        first = SQ_VALUE.matcher(first).replaceAll("'…'");
        Matcher w = COPY_WHERE.matcher(message);
        if (w.find()) {
            first += " (COPY " + w.group(1) + " " + w.group(2) + "번째 행" + (w.group(3) == null ? "" : ", 컬럼 " + w.group(3)) + ")";
        }
        return first.length() > 1000 ? first.substring(0, 1000) + "…" : first;
    }
}
