package kdms.cdc;

import java.util.Comparator;
import java.util.HexFormat;

/**
 * SQL Server LSN(Log Sequence Number, 로그 순번) 표기. Debezium 과 같은 'xxxxxxxx:xxxxxxxx:xxxx'(고정 폭 16진수 소문자)라서
 * 문자열 비교 = LSN 순서다(kdms.watermark·change_log 의 varchar(24) 컬럼도 이 표기).
 */
public final class Lsn {

    /** 변경 위치 하나. 같은 커밋 안에서는 change_lsn, 같은 change_lsn 이면(PK 를 바꾸는 UPDATE = 삭제 + 입력) event_serial_no 순서 */
    public record Position(String commitLsn, String changeLsn, long eventSerialNo) implements Comparable<Position> {

        private static final Comparator<Position> ORDER = Comparator.comparing(Position::commitLsn)
                .thenComparing(Position::changeLsn).thenComparingLong(Position::eventSerialNo);

        @Override
        public int compareTo(Position o) {
            return ORDER.compare(this, o);
        }

        @Override
        public String toString() {
            return commitLsn + "/" + changeLsn + "/" + eventSerialNo;
        }
    }

    private Lsn() {
    }

    /** binary(10) → 'xxxxxxxx:xxxxxxxx:xxxx'. null 이면 null */
    public static String format(byte[] lsn) {
        if (lsn == null) {
            return null;
        }
        if (lsn.length != 10) {
            throw new IllegalArgumentException("LSN 은 10바이트다: " + lsn.length);
        }
        String h = HexFormat.of().formatHex(lsn);
        return h.substring(0, 8) + ":" + h.substring(8, 16) + ":" + h.substring(16);
    }

    /** Debezium 오프셋 값(문자열, 없으면 "NULL" 또는 null)을 표기로. 모르는 형식이면 null */
    public static String normalize(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().toLowerCase(java.util.Locale.ROOT);
        return s.matches("[0-9a-f]{8}:[0-9a-f]{8}:[0-9a-f]{4}") ? s : null;
    }

    /** 둘 중 큰 것(null 은 가장 작다) */
    public static String max(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }
}
