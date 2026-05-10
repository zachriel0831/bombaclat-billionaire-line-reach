package com.zack.linerelay.push;

import com.zack.linerelay.config.LineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory runtime switchboard for normal push delivery and test-only target
 * filtering.
 */
@Service
public class PushModeService {

    private static final Logger log = LoggerFactory.getLogger(PushModeService.class);

    private final AtomicBoolean pushEnabled;
    private final AtomicBoolean pushTestOnly;

    /**
     * Seeds runtime mode from configuration. Atomics make webhook command changes
     * visible across request and scheduler threads without adding a database row.
     */
    public PushModeService(LineProperties props) {
        LineProperties.Push push = props.push();
        this.pushEnabled = new AtomicBoolean(push != null && push.enabled());
        this.pushTestOnly = new AtomicBoolean(push == null || push.testOnly());
    }

    public boolean isPushEnabled() {
        return pushEnabled.get();
    }

    public boolean isPushTestOnly() {
        return pushTestOnly.get();
    }

    /**
     * Runtime command path for safe testing: push is enabled but restricted to
     * test accounts.
     */
    public void enableTestMode(String source) {
        pushEnabled.set(true);
        pushTestOnly.set(true);
        log.warn("LINE push mode changed by {}: push_enabled=true push_test_only=true", source);
    }

    /**
     * Runtime command path for an immediate kill switch. Test-only is left as-is
     * because disabling push is the only behavior this command promises.
     */
    public void disableAllPushes(String source) {
        pushEnabled.set(false);
        log.warn("LINE push mode changed by {}: push_enabled=false", source);
    }
}
