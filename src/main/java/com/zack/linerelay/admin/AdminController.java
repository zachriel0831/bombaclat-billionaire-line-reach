package com.zack.linerelay.admin;

import com.zack.linerelay.config.LineProperties;
import com.zack.linerelay.db.BotTargetRepository;
import com.zack.linerelay.market.MarketAnalysisPoller;
import com.zack.linerelay.push.PushModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal operational endpoints for checking target resolution and manually
 * running the market-analysis push workflow.
 */
@RestController
@RequestMapping("/admin")
@ConditionalOnProperty(prefix = "line.mysql", name = "enabled", havingValue = "true")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final BotTargetRepository targetRepo;
    private final MarketAnalysisPoller poller;
    private final PushModeService pushModeService;

    public AdminController(
            BotTargetRepository targetRepo,
            MarketAnalysisPoller poller,
            PushModeService pushModeService
    ) {
        this.targetRepo = targetRepo;
        this.poller = poller;
        this.pushModeService = pushModeService;
    }

    /**
     * Operational endpoint for verifying the exact roster that a push would use
     * after active/test-only filtering has been applied.
     */
    @GetMapping("/list-targets")
    public Map<String, Object> listTargets() {
        List<String> groups = targetRepo.listActiveGroupIds();
        List<String> users = targetRepo.listActiveUserIds();
        log.info("admin_list_targets groups={} users={}", groups.size(), users.size());
        return Map.of(
                "groups", groups,
                "users", users,
                "total", groups.size() + users.size(),
                "push_enabled", pushModeService.isPushEnabled(),
                "push_test_only", pushModeService.isPushTestOnly()
        );
    }

    /**
     * Manual trigger for the same path used by scheduled pushes. This is useful
     * for smoke testing DB reads and LINE delivery without waiting for cron.
     */
    @PostMapping("/poll-market-analysis")
    public MarketAnalysisPoller.PollResult pollMarketAnalysis(
            @RequestParam(name = "date", required = false) String analysisDate,
            @RequestParam(name = "slot", required = false) String analysisSlot
    ) {
        return poller.pollOnce(analysisDate, analysisSlot);
    }
}
