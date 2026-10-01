package kdms.catalog;

import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;

import kdms.config.KdmsConfig;

/**
 * {@code kdms status} 와 웹 첫 화면이 보여 주는 내용. 원천·대상을 동시에 확인한다.
 */
public record StatusReport(
        String kdmsVersion,
        String jobName,
        String rulesSummary,
        OffsetDateTime checkedAt,
        ProbeResult source,
        ProbeResult target) {

    public boolean allConnected() {
        return source.connected() && target.connected();
    }

    public static StatusReport collect(String kdmsVersion, KdmsConfig cfg, String rulesSummary) {
        CompletableFuture<ProbeResult> src = CompletableFuture.supplyAsync(() -> DbProbe.probeSource(cfg.source()));
        CompletableFuture<ProbeResult> tgt = CompletableFuture.supplyAsync(() -> DbProbe.probeTarget(cfg.target()));
        return new StatusReport(kdmsVersion, cfg.jobName(), rulesSummary, OffsetDateTime.now(), src.join(), tgt.join());
    }
}
