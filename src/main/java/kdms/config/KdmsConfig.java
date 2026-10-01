package kdms.config;

import java.util.List;
import java.util.Map;

/**
 * 작업 설정(config/kdms.yml). 형식은 config/kdms.example.yml 참고.
 *
 * @param rules 변환 규칙 파일 경로. 비우면 jar 안의 기본 규칙(kdms-rules.yml)만 쓴다.
 */
public record KdmsConfig(
        String jobName,
        Endpoint source,
        Endpoint target,
        LoadSettings load,
        TableSelection tables,
        String rules,
        WebSettings web) {

    /**
     * DB 접속 정보. 비밀번호는 URL 에 넣지 않고 드라이버 속성으로만 넘긴다.
     *
     * @param properties JDBC 드라이버 속성(예: encrypt, trustServerCertificate, sslmode)
     */
    public record Endpoint(
            String host,
            int port,
            String database,
            String user,
            String password,
            Map<String, String> properties) {

        @Override
        public String toString() {
            // 비밀번호가 로그·화면에 새지 않게
            return user + "@" + host + ":" + port + "/" + database;
        }
    }

    /** 전체 적재 병렬도(plan.md §4.3). */
    public record LoadSettings(int tableParallelism, int chunksPerTable) {
    }

    /** 이관 대상 테이블. {@code schema.table} 형식, {@code *} 와일드카드. */
    public record TableSelection(List<String> include, List<String> exclude) {
    }

    /** 내장 웹. 기본은 127.0.0.1 에만 바인딩(plan.md §4.1). */
    public record WebSettings(String address, int port) {

        public boolean isLoopbackOnly() {
            return "127.0.0.1".equals(address) || "localhost".equals(address) || "::1".equals(address);
        }
    }
}
