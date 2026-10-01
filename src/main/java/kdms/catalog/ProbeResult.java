package kdms.catalog;

import java.util.List;

/**
 * DB 하나의 접속 확인 결과. CLI status 와 웹 첫 화면이 같이 쓴다.
 *
 * @param role      "원천" / "대상"
 * @param target    화면에 보일 접속 대상(user@host:port/db, 비밀번호 없음)
 * @param connected 접속 성공 여부
 * @param elapsedMs 접속·조회에 걸린 시간
 * @param items     항목(이름, 값) 목록
 * @param warnings  접속은 됐지만 다음 단계 전에 고칠 것(예: CDC 꺼짐)
 * @param error     접속 실패 원문(드라이버 메시지)
 */
public record ProbeResult(
        String role,
        String target,
        boolean connected,
        long elapsedMs,
        List<Item> items,
        List<String> warnings,
        String error) {

    public record Item(String name, String value) {
    }

    public static ProbeResult failed(String role, String target, long elapsedMs, String error) {
        return new ProbeResult(role, target, false, elapsedMs, List.of(), List.of(), error);
    }
}
