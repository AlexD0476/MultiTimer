package com.example.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class ManagedTimerTest {
    @Test
    public void finiteIntervalRunsExactlyConfiguredCycles() {
        ManagedTimer timer = createIntervalTimer(3);

        assertTrue(timer.hasNextPhaseAfterCompletion());
        advancePhase(timer, 1000L);
        assertEquals(2, timer.getCompletedCycles());
        assertTrue(timer.hasNextPhaseAfterCompletion());
        advancePhase(timer, 2000L);
        assertEquals(3, timer.getCompletedCycles());
        assertFalse(timer.hasNextPhaseAfterCompletion());
    }

    @Test
    public void zeroRepeatCountMeansUnboundedInterval() {
        ManagedTimer timer = createIntervalTimer(0);

        for (int cycle = 0; cycle < 100; cycle++) {
            assertTrue(timer.hasNextPhaseAfterCompletion());
            advancePhase(timer, cycle * 1000L);
        }

        assertEquals(101, timer.getCompletedCycles());
        assertTrue(timer.hasNextPhaseAfterCompletion());
    }

    @Test
    public void intervalConfirmationIsOptionalAndSurvivesTemplateStart() {
        SavedTimer automaticTemplate = createIntervalTemplate(false);
        SavedTimer confirmationTemplate = createIntervalTemplate(true);

        assertFalse(automaticTemplate.waitsForIntervalConfirmation());
        assertFalse(createIntervalRun(automaticTemplate).repeatsCompletionAnnouncement());
        assertTrue(confirmationTemplate.recordStarted(10L).waitsForIntervalConfirmation());

        ManagedTimer automaticRun = createIntervalRun(automaticTemplate);
        automaticRun.markPhaseAwaitingAnnouncement();
        assertFalse(automaticRun.requiresPhaseConfirmation());
        assertTrue(automaticRun.advanceAfterAnnouncement(2000L));

        ManagedTimer confirmationRun = createIntervalRun(confirmationTemplate);
        assertTrue(confirmationRun.repeatsCompletionAnnouncement());
        confirmationRun.markPhaseAwaitingAnnouncement();
        assertTrue(confirmationRun.requiresPhaseConfirmation());
        assertEquals(1, confirmationRun.getCompletedCycles());
        assertTrue(confirmationRun.advanceAfterAnnouncement(2000L));
        assertEquals(2, confirmationRun.getCompletedCycles());
    }

    @Test
    public void pendingConfirmationDoesNotTriggerCompletionAgainOnEachTick() {
        ManagedTimer timer = createIntervalRun(createIntervalTemplate(true));
        timer.markPhaseAwaitingAnnouncement();
        timer.markCompletionAnnounced();

        assertFalse(timer.shouldComplete(timer.getEndTimeMillis() + 1000L));
        assertTrue(timer.isPhaseAwaitingAnnouncement());
        assertTrue(timer.isCompletionAnnounced());
    }

    @Test
    public void mutedConfirmationStaysPendingAndResetsForNextPhase() {
        ManagedTimer timer = createIntervalRun(createIntervalTemplate(true));
        timer.markPhaseAwaitingAnnouncement();
        timer.mutePhaseAnnouncement();

        assertTrue(timer.isPhaseAwaitingAnnouncement());
        assertTrue(timer.requiresPhaseConfirmation());
        assertTrue(timer.isPhaseAnnouncementMuted());
        assertTrue(new ManagedTimer(timer).isPhaseAnnouncementMuted());

        assertTrue(timer.advanceAfterAnnouncement(2000L));
        assertFalse(timer.isPhaseAnnouncementMuted());
        timer.markPhaseAwaitingAnnouncement();
        assertFalse(timer.isPhaseAnnouncementMuted());
    }

    @Test
    public void groupAdvancesOnlyAfterAnnouncementAndUsesNextStepText() {
        TimerStep firstStep = new TimerStep("Vorbereitung", 1000L, "Vorbereitung fertig");
        TimerStep secondStep = new TimerStep("Pause", 2000L, "Pause vorbei");
        ManagedTimer timer = new ManagedTimer(1L, 2L, "Training", 3000L, 0L, 1000L,
                5000L, 100, "", TimerType.GROUP, 0, 1, 0,
                Arrays.asList(firstStep, secondStep), false);

        assertFalse(timer.advanceAfterAnnouncement(1000L));
        timer.markPhaseAwaitingAnnouncement();
        assertTrue(timer.advanceAfterAnnouncement(1500L));

        assertEquals(1, timer.getCurrentStepIndex());
        assertEquals(2000L, timer.getDurationMillis());
        assertEquals("Pause", timer.getAnnouncementName());
        assertEquals("Pause vorbei", timer.getCompletionText());
        assertEquals(3500L, timer.getEndTimeMillis());
    }

    @Test
    public void finalGroupStepCompletesOnlyWhenAcknowledged() {
        TimerStep firstStep = new TimerStep("Vorbereitung", 1000L, "Los");
        TimerStep finalStep = new TimerStep("Abschluss", 2000L, "Fertig");
        ManagedTimer timer = new ManagedTimer(1L, 2L, "Training", 2000L, 0L, 2000L,
            3000L, 100, "Training beendet", TimerType.GROUP, 0, 1, 1,
                Arrays.asList(firstStep, finalStep), false);

        timer.markPhaseAwaitingAnnouncement();

        assertEquals("Fertig", timer.getCompletionText());
        assertTrue(timer.hasNextPhaseAfterCompletion());
        assertFalse(timer.isCompleted());
        assertFalse(timer.advanceAfterAnnouncement(2000L));
        assertTrue(timer.isCompleted());
        assertFalse(timer.isPhaseAwaitingAnnouncement());
        assertEquals("Training beendet", timer.getCompletionText());
    }

    private ManagedTimer createIntervalTimer(int repeatCount) {
        return new ManagedTimer(1L, 2L, "Intervall", 1000L, 0L, 1000L,
                5000L, 100, "Weiter", TimerType.INTERVAL, repeatCount, 1, 0,
                Collections.emptyList(), false);
    }

    private SavedTimer createIntervalTemplate(boolean waitForConfirmation) {
        return new SavedTimer(1L, "Intervall", 1000L, 5000L, 100, "Weiter",
                0L, 0L, 0, TimerType.INTERVAL, 2, waitForConfirmation, Collections.emptyList());
    }

    private ManagedTimer createIntervalRun(SavedTimer template) {
        return new ManagedTimer(2L, template.getId(), template.getName(), template.getDurationMillis(),
                1000L, 2000L, template.getAnnouncementIntervalMillis(), template.getAlarmVolume(),
                template.getCompletionText(), template.getTimerType(), template.getRepeatCount(),
                template.waitsForIntervalConfirmation(), 1, 0, template.getSteps(), false);
    }

    private void advancePhase(ManagedTimer timer, long now) {
        timer.markPhaseAwaitingAnnouncement();
        assertTrue(timer.advanceAfterAnnouncement(now));
    }
}