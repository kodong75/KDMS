package kdms.config;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 원천(MS-SQL)·대상(PG) JDBC 연결. 비밀번호는 URL 이 아니라 {@link Properties} 로만 넘긴다(URL 은 로그·화면에 나올 수 있다).
 * <p>
 * DriverManager 를 거치지 않고 드라이버를 직접 쓴다. 실행 jar 안에서 DriverManager 는 처음 불린 스레드의
 * 컨텍스트 클래스로더로 드라이버를 찾는데, 공용 스레드 풀에서 처음 불리면 jar 안 드라이버를 못 찾는다("No suitable driver").
 */
public final class Jdbc {

    /** 접속 시도 제한 시간(초). 노트북 DB 가 꺼져 있을 때 status 가 오래 멈추지 않게. */
    public static final int LOGIN_TIMEOUT_SECONDS = 5;

    private Jdbc() {
    }

    public static String sourceUrl(KdmsConfig.Endpoint e) {
        return "jdbc:sqlserver://" + e.host() + ":" + e.port() + ";databaseName=" + e.database();
    }

    public static String targetUrl(KdmsConfig.Endpoint e) {
        return "jdbc:postgresql://" + e.host() + ":" + e.port() + "/" + e.database();
    }

    public static Connection openSource(KdmsConfig.Endpoint e) throws SQLException {
        Properties p = new Properties();
        p.setProperty("applicationName", "kdms");
        p.setProperty("loginTimeout", String.valueOf(LOGIN_TIMEOUT_SECONDS));
        // 운영 기본값은 인증서 검증. 시험 환경은 설정 파일에서 trustServerCertificate=true (plan.md §2)
        p.setProperty("encrypt", "true");
        p.putAll(e.properties());
        p.setProperty("user", e.user());
        p.setProperty("password", e.password());
        return connect(new com.microsoft.sqlserver.jdbc.SQLServerDriver(), sourceUrl(e), p);
    }

    public static Connection openTarget(KdmsConfig.Endpoint e) throws SQLException {
        Properties p = new Properties();
        p.setProperty("ApplicationName", "kdms");
        p.setProperty("connectTimeout", String.valueOf(LOGIN_TIMEOUT_SECONDS));
        p.setProperty("loginTimeout", String.valueOf(LOGIN_TIMEOUT_SECONDS));
        p.putAll(e.properties());
        p.setProperty("user", e.user());
        p.setProperty("password", e.password());
        return connect(new org.postgresql.Driver(), targetUrl(e), p);
    }

    private static Connection connect(Driver driver, String url, Properties p) throws SQLException {
        Connection c = driver.connect(url, p);
        if (c == null) {
            throw new SQLException("드라이버가 URL 을 받지 않았다: " + url);
        }
        return c;
    }
}
