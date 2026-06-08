package com.zack.linerelay.macro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Cron wrapper for Taiwan-date macro calendar reminders.
 */
@Service
@ConditionalOnBean(MacroCalendarReminderService.class)
@ConditionalOnProperty(prefix = "line.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MacroCalendarScheduler {

    private static final Logger log = LoggerFactory.getLogger(MacroCalendarScheduler.class);

    private final MacroCalendarReminderService reminderService;
    private final Clock scheduleClock;

    @Autowired
    public MacroCalendarScheduler(
            MacroCalendarReminderService reminderService,
            @Value("${line.schedule.zone:Asia/Taipei}") String scheduleZone
    ) {
        this(reminderService, Clock.system(ZoneId.of(scheduleZone)));
    }

    MacroCalendarScheduler(MacroCalendarReminderService reminderService, Clock scheduleClock) {
        this.reminderService = reminderService;
        this.scheduleClock = scheduleClock;
    }

    @Scheduled(cron = "${line.schedule.macro-calendar-reminder-cron}", zone = "${line.schedule.zone}")
    public void pushMacroCalendarReminder() {
        LocalDate todayTaipei = LocalDate.now(scheduleClock);
        log.info("scheduled_macro_calendar_reminder started reminder_date={}", todayTaipei);
        MacroCalendarReminderService.ReminderResult result =
                reminderService.sendTomorrowReminders(todayTaipei);
        log.info("scheduled_macro_calendar_reminder finished reminder_date={} ok={} releases={} pushed={} skipped_by_toggle={} skipped_by_rate_limit={} skip_reason={}",
                todayTaipei, result.ok(), result.releases(), result.pushed(), result.skippedByToggle(),
                result.skippedByRateLimit(), result.skipReason());
    }
}
