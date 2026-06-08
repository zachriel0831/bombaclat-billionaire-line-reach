package com.zack.linerelay.macro;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MacroCalendarSchedulerTest {

    @Test
    void pushMacroCalendarReminderUsesCurrentTaipeiDate() {
        MacroCalendarReminderService service = mock(MacroCalendarReminderService.class);
        when(service.sendTomorrowReminders(LocalDate.parse("2026-06-09")))
                .thenReturn(new MacroCalendarReminderService.ReminderResult(
                        true, LocalDate.parse("2026-06-09"), 1, 1, 0, 0, null));
        MacroCalendarScheduler scheduler = new MacroCalendarScheduler(
                service,
                Clock.fixed(Instant.parse("2026-06-09T00:00:00Z"), ZoneId.of("Asia/Taipei")));

        scheduler.pushMacroCalendarReminder();

        verify(service).sendTomorrowReminders(LocalDate.parse("2026-06-09"));
    }
}
