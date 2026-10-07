package com.example.multitimer;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/** Zeigt Timerbestaetigungen oberhalb anderer Apps und auf dem Sperrbildschirm. */
public final class TimerAlertActivity extends AppCompatActivity {
    static final String ACTION_CONFIRM_PHASE = "com.example.multitimer.CONFIRM_TIMER_PHASE";
    static final String ACTION_CONFIRM_SWIPE = "com.example.multitimer.CONFIRM_TIMER_SWIPE";

    private AlertDialog alertDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prepareWindow();
        showAlert(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (alertDialog != null) {
            alertDialog.dismiss();
        }
        showAlert(intent);
    }

    private void prepareWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showAlert(Intent intent) {
        TimerService.ensureTimersLoaded(this);
        long timerId = intent == null ? -1L : intent.getLongExtra(TimerService.EXTRA_TIMER_ID, -1L);
        ManagedTimer timer = findTimer(timerId);
        if (timer == null) {
            finish();
            return;
        }

        if (ACTION_CONFIRM_PHASE.equals(intent.getAction()) && timer.requiresPhaseConfirmation()) {
            showPhaseConfirmation(timer);
        } else if (ACTION_CONFIRM_SWIPE.equals(intent.getAction())) {
            showSwipeConfirmation(timer);
        } else {
            finish();
        }
    }

    private ManagedTimer findTimer(long timerId) {
        List<ManagedTimer> timers = TimerService.getTimersSnapshot();
        for (ManagedTimer timer : timers) {
            if (timer.getId() == timerId) {
                return timer;
            }
        }
        return null;
    }

    private void showPhaseConfirmation(ManagedTimer timer) {
        boolean group = timer.getTimerType() == TimerType.GROUP;
        String message = group
                ? getString(R.string.group_step_confirmation_message, timer.getAnnouncementName(),
                        timer.getCurrentStepIndex() + 1, timer.getSteps().size())
                : getString(R.string.interval_confirmation_message, timer.getProgressLabel());
        int positiveLabel = group
                ? (timer.hasNextGroupStep()
                        ? R.string.action_confirm_next_group_step
                        : R.string.action_finish_timer_group)
                : R.string.action_confirm_next_interval;

        alertDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(timer.getName())
                .setMessage(message)
                .setNegativeButton(R.string.action_cancel_timer, (dialog, which) -> {
                    TimerService.enqueueCancelTimer(this, timer.getId());
                    finish();
                })
                .setNeutralButton(timer.isPhaseAnnouncementMuted()
                        ? R.string.action_phase_announcement_muted
                        : R.string.action_mute_phase_announcement, null)
                .setPositiveButton(positiveLabel, (dialog, which) -> {
                    TimerService.enqueueAcknowledgeTimerPhase(this, timer.getId());
                    finish();
                })
                .create();
        alertDialog.setCanceledOnTouchOutside(false);
        alertDialog.setCancelable(false);
        alertDialog.setOnDismissListener(dialog -> finish());
        alertDialog.show();
        android.widget.Button muteButton = alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (timer.isPhaseAnnouncementMuted()) {
            muteButton.setEnabled(false);
        } else {
            muteButton.setOnClickListener(view -> {
                TimerService.enqueueMutePhaseAnnouncement(this, timer.getId());
                muteButton.setText(R.string.action_phase_announcement_muted);
                muteButton.setEnabled(false);
            });
        }
    }

    private void showSwipeConfirmation(ManagedTimer timer) {
        alertDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_cancel_timer_title)
                .setMessage(getString(R.string.dialog_cancel_timer_message, timer.getName()))
                .setNegativeButton(R.string.action_keep_running, (dialog, which) -> {
                    TimerService.enqueueRefreshTimerNotification(this, timer.getId());
                    finish();
                })
                .setPositiveButton(R.string.action_cancel_timer, (dialog, which) -> {
                    if (timer.isCompleted()) {
                        TimerService.enqueueDismissNotification(this, timer.getId());
                    } else {
                        TimerService.enqueueCancelTimer(this, timer.getId());
                    }
                    finish();
                })
                .create();
        alertDialog.setCanceledOnTouchOutside(false);
        alertDialog.setCancelable(false);
        alertDialog.setOnDismissListener(dialog -> finish());
        alertDialog.show();
    }
}