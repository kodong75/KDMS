package kdms.config;

import java.sql.Connection;
import java.sql.SQLException;

/** 원천·대상 연결을 여는 곳. 적재·검증은 구간·테이블마다 새 연결을 연다. 시험은 다른 DB 를 줄 수 있다. */
public interface Connections {

    Connection source() throws SQLException;

    Connection target() throws SQLException;

    static Connections of(KdmsConfig cfg) {
        return new Connections() {
            @Override
            public Connection source() throws SQLException {
                return Jdbc.openSource(cfg.source());
            }

            @Override
            public Connection target() throws SQLException {
                return Jdbc.openTarget(cfg.target());
            }
        };
    }
}
