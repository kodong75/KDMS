package kdms.config;

/** 설정·규칙 파일 오류. 메시지는 사용자에게 그대로 보이므로 비밀번호 값을 넣지 않는다. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }
}
