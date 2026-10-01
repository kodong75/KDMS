package kdms.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import kdms.catalog.StatusReport;
import kdms.cli.VersionProvider;
import kdms.config.KdmsConfig;

/** 첫 화면과 상태 REST(Representational State Transfer) API. 1단계는 조회만 한다. */
@Controller
public class StatusController {

    private final KdmsConfig cfg;
    private final KdmsWebApplication.RulesSummary rules;

    public StatusController(KdmsConfig cfg, KdmsWebApplication.RulesSummary rules) {
        this.cfg = cfg;
        this.rules = rules;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("report", collect());
        return "index";
    }

    @GetMapping("/api/status")
    @ResponseBody
    public StatusReport status() {
        return collect();
    }

    private StatusReport collect() {
        return StatusReport.collect(VersionProvider.version(), cfg, rules.text());
    }
}
