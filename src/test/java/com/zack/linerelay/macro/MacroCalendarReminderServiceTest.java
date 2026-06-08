package com.zack.linerelay.macro;

import com.zack.linerelay.db.BotTarget;
import com.zack.linerelay.db.BotTargetRepository;
import com.zack.linerelay.db.MacroRelease;
import com.zack.linerelay.db.MacroReleaseCalendarRepository;
import com.zack.linerelay.push.LinePushClient;
import com.zack.linerelay.push.PushMessageType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MacroCalendarReminderServiceTest {

    private static final LocalDate REMINDER_DATE = LocalDate.parse("2026-06-09");

    @Test
    void sendTomorrowRemindersPushesAggregatedMessageAndMarksRows() {
        MacroReleaseCalendarRepository releaseRepo = mock(MacroReleaseCalendarRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        MacroRelease cpi = release(1L, "us_cpi", "U.S. CPI", "May 2026",
                LocalDateTime.parse("2026-06-10T20:30"));
        MacroRelease ppi = release(2L, "us_ppi", "U.S. PPI", "May 2026",
                LocalDateTime.parse("2026-06-11T20:30"));
        when(releaseRepo.findPendingForReminderDate(REMINDER_DATE)).thenReturn(List.of(cpi, ppi));
        when(targetRepo.listActiveTargets()).thenReturn(List.of(BotTarget.user("U1")));
        when(pushClient.push(eq(PushMessageType.MACRO_CALENDAR), eq("U1"), anyString()))
                .thenReturn(LinePushClient.PushAttempt.sent());

        MacroCalendarReminderService service =
                new MacroCalendarReminderService(releaseRepo, targetRepo, pushClient);
        MacroCalendarReminderService.ReminderResult result = service.sendTomorrowReminders(REMINDER_DATE);

        assertTrue(result.ok());
        assertEquals(2, result.releases());
        assertEquals(1, result.pushed());
        verify(releaseRepo).markReminderPushed(List.of(1L, 2L));
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(pushClient).push(eq(PushMessageType.MACRO_CALENDAR), eq("U1"), message.capture());
        assertTrue(message.getValue().contains("明天有美國重要經濟數據公布"));
        assertTrue(message.getValue().contains("06/10 20:30 U.S. CPI"));
        assertTrue(message.getValue().contains("AI 股可能修正"));
        assertTrue(message.getValue().contains("資料來源"));
    }

    @Test
    void sendTomorrowRemindersDoesNotMarkPushedWhenToggleSkips() {
        MacroReleaseCalendarRepository releaseRepo = mock(MacroReleaseCalendarRepository.class);
        BotTargetRepository targetRepo = mock(BotTargetRepository.class);
        LinePushClient pushClient = mock(LinePushClient.class);
        MacroRelease cpi = release(1L, "us_cpi", "U.S. CPI", "May 2026",
                LocalDateTime.parse("2026-06-10T20:30"));
        when(releaseRepo.findPendingForReminderDate(REMINDER_DATE)).thenReturn(List.of(cpi));
        when(targetRepo.listActiveTargets()).thenReturn(List.of(BotTarget.user("U1")));
        when(pushClient.push(eq(PushMessageType.MACRO_CALENDAR), eq("U1"), anyString()))
                .thenReturn(LinePushClient.PushAttempt.toggleSkipped());

        MacroCalendarReminderService service =
                new MacroCalendarReminderService(releaseRepo, targetRepo, pushClient);
        MacroCalendarReminderService.ReminderResult result = service.sendTomorrowReminders(REMINDER_DATE);

        assertTrue(result.ok());
        assertEquals(0, result.pushed());
        assertEquals(1, result.skippedByToggle());
        verify(releaseRepo, never()).markReminderPushed(List.of(1L));
        verify(releaseRepo).markReminderFailed(eq(List.of(1L)), eq("not_delivered"), anyString());
    }

    @Test
    void buildMessageKeepsRetailSalesNote() {
        MacroCalendarReminderService service =
                new MacroCalendarReminderService(mock(MacroReleaseCalendarRepository.class),
                        mock(BotTargetRepository.class), mock(LinePushClient.class));
        String message = service.buildMessage(REMINDER_DATE, List.of(
                release(7L, "us_retail_sales", "U.S. Retail Sales", "May 2026",
                        LocalDateTime.parse("2026-06-17T20:30"))));

        assertTrue(message.contains("美國消費力"));
        assertTrue(message.contains("消費韌性強"));
    }

    @Test
    void buildMessageGroupsMacroAndEarningsRows() {
        MacroCalendarReminderService service =
                new MacroCalendarReminderService(mock(MacroReleaseCalendarRepository.class),
                        mock(BotTargetRepository.class), mock(LinePushClient.class));
        String message = service.buildMessage(REMINDER_DATE, List.of(
                release(1L, "us_cpi", "U.S. CPI", "May 2026",
                        LocalDateTime.parse("2026-06-10T20:30")),
                release(2L, "earnings_nvda", "NVDA NVIDIA Earnings (盤後)", "Apr/2026",
                        LocalDateTime.parse("2026-06-11T04:05"))));

        assertTrue(message.contains("明天有重要市場行事曆"));
        assertTrue(message.contains("美國經濟數據"));
        assertTrue(message.contains("權值股財報"));
        assertTrue(message.contains("06/11 04:05 NVDA NVIDIA Earnings"));
        assertTrue(message.contains("AI 供應鏈"));
    }

    private MacroRelease release(
            long id,
            String indicatorCode,
            String indicatorName,
            String periodLabel,
            LocalDateTime releaseAtTaipei
    ) {
        return new MacroRelease(
                id,
                indicatorCode,
                indicatorName,
                periodLabel,
                indicatorName + " for " + periodLabel,
                releaseAtTaipei,
                "U.S. Bureau of Labor Statistics",
                "https://example.test",
                5);
    }
}
