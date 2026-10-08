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
        SyncSettings sync,
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

    /**
     * 전체 적재(plan.md §4.3).
     *
     * @param tableParallelism 동시에 적재하는 테이블 수
     * @param chunksPerTable   테이블 하나를 나누는 PK 구간 수(= 테이블 안에서 동시에 도는 구간 수)
     * @param isolation        원천 읽기 격리 수준: snapshot(기본, 원천 DB 에 ALLOW_SNAPSHOT_ISOLATION ON 필요) | read_committed
     */
    public record LoadSettings(int tableParallelism, int chunksPerTable, String isolation) {
    }

    /**
     * 변경분 수집·반영(4단계, docs/cdc.md).
     *
     * @param batchSize     반영기가 한 트랜잭션에 적용하는 변경 수
     * @param pollMs        반영할 변경이 없을 때 다시 볼 때까지 쉬는 시간
     * @param statusSeconds 진행·지연을 화면에 찍는 간격(초)
     */
    public record SyncSettings(int batchSize, int pollMs, int statusSeconds) {
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
