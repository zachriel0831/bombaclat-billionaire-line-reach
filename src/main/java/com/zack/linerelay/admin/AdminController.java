package com.zack.linerelay.admin;

import com.zack.linerelay.config.LineProperties;
import com.zack.linerelay.db.BotTargetRepository;
import com.zack.linerelay.market.MarketAnalysisPoller;
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

@RestController
@RequestMapping("/admin")
@ConditionalOnProperty(prefix = "line.mysql", name = "enabled", havingValue = "true")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final BotTargetRepository targetRepo;
    private final MarketAnalysisPoller poller;
    private final LineProperties lineProperties;

    public AdminController(
            BotTargetRepository targetRepo,
            MarketAnalysisPoller poller,
            LineProperties lineProperties
    ) {
        this.targetRepo = targetRepo;
        this.poller = poller;
        this.lineProperties = lineProperties;
    }

    @GetMapping("/list-targets")
    public Map<String, Object> listTargets() {
        List<String> groups = targetRepo.listActiveGroupIds();
        List<String> users = targetRepo.listActiveUserIds();
        log.info("admin_list_targets groups={} users={}", groups.size(), users.size());
        return Map.of(
                "groups", groups,
                "users", users,
                "total", groups.size() + users.size(),
                "push_enabled", lineProperties.push() != null && lineProperties.push().enabled()
        );
    }

    @PostMapping("/poll-market-analysis")
    public MarketAnalysisPoller.PollResult pollMarketAnalysis(
            @RequestParam(name = "date", required = false) String analysisDate,
            @RequestParam(name = "slot", required = false) String analysisSlot
    ) {
        return poller.pollOnce(analysisDate, analysisSlot);
    }
}
