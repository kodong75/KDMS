package kdms.web;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;

import kdms.catalog.StatusReport;
import kdms.cdc.CaptureStore;
import kdms.cli.VersionProvider;
import kdms.config.Jdbc;
import kdms.config.KdmsConfig;
import kdms.load.SafeMessage;
import kdms.state.JobView;

/**
 * 화면과 REST(Representational State Transfer) API. 조회는 GET, 명령 실행은 POST + 화면이 받은 토큰 헤더
 * (다른 사이트가 이 주소로 몰래 보내는 요청(CSRF, Cross-Site Request Forgery)은 사용자 정의 헤더를 붙일 수 없다).
 */
@Controller
public class StatusController {

    /** 작업 화면에 보이는 최근 기록 수 */
    static final int EVENTS = 15;

    /**
     * @param view    작업 상태(대상에 못 붙으면 null)
     * @param running 지금 이 작업을 돌리는 명령(sync·load·cutover, 다른 프로세스 포함)
     * @param error   대상 조회 실패 이유
     */
    public record JobStatus(OffsetDateTime at, int syncStatusSeconds, JobView view, List<String> running, String error) {
    }

    private final KdmsConfig cfg;
    private final KdmsWebApplication.RulesSummary rules;
    private final TaskService tasks;
    private final KdmsWebApplication.Token token;

    public StatusController(KdmsConfig cfg, KdmsWebApplication.RulesSummary rules, TaskService tasks, KdmsWebApplication.Token token) {
        this.cfg = cfg;
        this.rules = rules;
        this.tasks = tasks;
        this.token = token;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("report", collect());
        model.addAttribute("token", token.value());
        model.addAttribute("actions", tasks.enabled());
        return "index";
    }

    @GetMapping("/api/status")
    @ResponseBody
    public StatusReport status() {
        return collect();
    }

    @GetMapping("/api/job")
    @ResponseBody
    public JobStatus job() {
        try (Connection c = Jdbc.openTarget(cfg.target())) {
            return new JobStatus(OffsetDateTime.now(), cfg.sync().statusSeconds(), JobView.read(c, cfg.jobName(), EVENTS),
                    CaptureStore.runningNow(c, cfg.jobName()), null);
        } catch (Exception e) {
            return new JobStatus(OffsetDateTime.now(), cfg.sync().statusSeconds(), null, List.of(), "대상 " + cfg.target() + " 조회 실패: " + SafeMessage.of(e));
        }
    }

    @GetMapping("/api/tasks")
    @ResponseBody
    public List<TaskService.Task> tasks() {
        return tasks.tasks();
    }

    @PostMapping("/api/tasks/{kind}")
    @ResponseBody
    public ResponseEntity<?> start(@PathVariable String kind, @RequestHeader(name = "X-KDMS-Token", required = false) String t) {
        return act(t, () -> tasks.start(kind));
    }

    @PostMapping("/api/tasks/{kind}/stop")
    @ResponseBody
    public ResponseEntity<?> stop(@PathVariable String kind, @RequestHeader(name = "X-KDMS-Token", required = false) String t) {
        return act(t, () -> tasks.stop(kind));
    }

    private ResponseEntity<?> act(String t, java.util.function.Supplier<TaskService.Task> action) {
        if (t == null || !token.value().equals(t)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "화면 토큰이 없거나 다르다. 화면을 새로 고친다"));
        }
        try {
            return ResponseEntity.ok(action.get());
        } catch (TaskService.Rejected e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    private StatusReport collect() {
        return StatusReport.collect(VersionProvider.version(), cfg, rules.text());
    }
}
