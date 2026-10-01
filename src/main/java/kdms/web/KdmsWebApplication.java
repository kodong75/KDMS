package kdms.web;

import java.sql.Connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import kdms.config.Jdbc;
import kdms.config.KdmsConfig;
import kdms.state.SchemaInstaller;

/**
 * 내장 웹(Thymeleaf + 직접 작성한 JS·CSS, 외부 CDN 없음). {@code kdms web} 명령이 띄운다.
 * {@link KdmsConfig} 와 {@link RulesSummary} 는 명령이 빈으로 넣어 준다.
 */
@SpringBootApplication
public class KdmsWebApplication {

    private static final Logger log = LoggerFactory.getLogger(KdmsWebApplication.class);

    public record RulesSummary(String text) {
    }

    /** 시작할 때 관리 스키마를 최신으로(plan.md §1.2). 대상에 못 붙어도 화면은 띄워 오류를 보여 준다. */
    @Bean
    ApplicationRunner installSchemaOnStart(KdmsConfig cfg) {
        return (ApplicationArguments args) -> {
            try (Connection c = Jdbc.openTarget(cfg.target())) {
                log.info("관리 스키마 kdms 버전 {}", SchemaInstaller.install(c));
            } catch (Exception e) {
                log.warn("관리 스키마를 확인하지 못했다({}): {}", cfg.target(), e.getMessage());
            }
        };
    }
}
