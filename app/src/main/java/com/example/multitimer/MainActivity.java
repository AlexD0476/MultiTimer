package com.example.multitimer;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/**
 * Hauptbildschirm der App.
 *
 * <p>Die Activity steuert Eingabedialoge, zeigt die Timerliste an und delegiert
 * alle Timeroperationen an den {@link TimerService}.</p>
 */
public final class MainActivity extends AppCompatActivity {
    private static final long ANNOUNCE_SINGLE = 0L;
    private static final long ANNOUNCE_EVERY_SECOND = 1000L;
    private static final long ANNOUNCE_EVERY_THREE_SECONDS = 3000L;
    private static final long ANNOUNCE_EVERY_FIVE_SECONDS = 5000L;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshTimers();
            uiHandler.postDelayed(this, 1000L);
        }
    };

    private MaterialButton newTimerButton;
    private ImageButton infoButton;
    private TextView emptyStateView;
    private TextView timerStartedBanner;
    private View uiInteractionBlocker;
    private boolean dialogUiBlocked;
    private final Runnable hideBannerRunnable = () -> timerStartedBanner.setVisibility(View.GONE);
    private RecyclerView recyclerView;
    private TimerAdapter adapter;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TimerService.ensureTimersLoaded(this);
        setContentView(R.layout.activity_main);

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                }
        );

        newTimerButton = findViewById(R.id.newTimerButton);
        infoButton = findViewById(R.id.infoButton);
        emptyStateView = findViewById(R.id.emptyState);
        timerStartedBanner = findViewById(R.id.timerStartedBanner);
        uiInteractionBlocker = findViewById(R.id.uiInteractionBlocker);
        recyclerView = findViewById(R.id.timerRecyclerView);

        adapter = new TimerAdapter(new TimerAdapter.OnTimerActionListener() {
            @Override
            public void onRestartTimer(ManagedTimer timer) {
                TimerService.enqueueRestartTimer(MainActivity.this, timer.getId());
                refreshTimers();
                showStartedBanner(timer.getName());
            }

            @Override
            public void onCancelTimer(ManagedTimer timer) {
                showCancelTimerDialog(timer);
            }

            @Override
            public void onDeleteTimer(ManagedTimer timer) {
                showDeleteTimerDialog(timer);
            }

            @Override
            public void onDismissNotification(ManagedTimer timer) {
                TimerService.enqueueDismissNotification(MainActivity.this, timer.getId());
                refreshTimers();
            }

            @Override
            public void onEditTimer(ManagedTimer timer) {
                showEditTimerDialog(timer);
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        infoButton.setOnClickListener(v -> showInfoDialog());
        newTimerButton.setClickable(true);
        newTimerButton.setEnabled(true);
        newTimerButton.bringToFront();
        newTimerButton.setOnClickListener(v -> {
            if (!dialogUiBlocked) {
                showCreateTimerDialog();
            }
        });
        emptyStateView.setOnClickListener(v -> {
            if (!dialogUiBlocked) {
                showCreateTimerDialog();
            }
        });
        recyclerView.setOnClickListener(v -> {
            if (adapter.getItemCount() == 0) {
                showCreateTimerDialog();
            }
        });

        maybeRequestNotificationPermission();
        refreshTimers();
    }

    @Override
    protected void onStart() {
        super.onStart();
        TimerService.ensureServiceRunningForActiveTimers(this);
        uiHandler.post(refreshRunnable);
    }

    @Override
    protected void onStop() {
        uiHandler.removeCallbacks(refreshRunnable);
        super.onStop();
    }

    /**
     * Erstellt einen neuen Timer aus Dialogwerten und zeigt ein kurzes Feedback-Banner.
        *
        * @param timerName Name des neuen Timers
        * @param minutes Minutenanteil der Dauer
        * @param seconds Sekundenanteil der Dauer
     */
    private void startTimer(String timerName, int minutes, int seconds, long announcementIntervalMillis) {
        startTimer(timerName, minutes, seconds, announcementIntervalMillis, ManagedTimer.DEFAULT_ALARM_VOLUME);
    }

    private void startTimer(String timerName, int minutes, int seconds, long announcementIntervalMillis, int alarmVolume) {
        long durationMillis = ((minutes * 60L) + seconds) * 1000L;
        TimerService.enqueueCreateTimer(this, timerName, durationMillis, announcementIntervalMillis, alarmVolume);
        refreshTimers();
        uiHandler.removeCallbacks(hideBannerRunnable);
        timerStartedBanner.setText(timerName + " angelegt");
        timerStartedBanner.setVisibility(View.VISIBLE);
        uiHandler.postDelayed(hideBannerRunnable, 2500L);
    }

    /**
     * Zeigt das Overlay-Banner fuer einen gestarteten Timer.
        *
        * @param timerName Name des gestarteten Timers
     */
    private void showStartedBanner(String timerName) {
        uiHandler.removeCallbacks(hideBannerRunnable);
        timerStartedBanner.setText(timerName + " gestartet");
        timerStartedBanner.setVisibility(View.VISIBLE);
        uiHandler.postDelayed(hideBannerRunnable, 3000L);
    }

    /**
     * Setzt eine vordefinierte Dauer in die Dialogeingabefelder.
        *
        * @param minutesInput Eingabefeld fuer Minuten
        * @param secondsInput Eingabefeld fuer Sekunden
        * @param minutes Minutenwert des Presets
        * @param seconds Sekundenwert des Presets
     */
    private void applyPreset(TextInputEditText minutesInput, TextInputEditText secondsInput, int minutes, int seconds) {
        minutesInput.setText(String.valueOf(minutes));
        secondsInput.setText(String.valueOf(seconds));
    }

    /**
     * Oeffnet den Dialog zum Anlegen eines neuen Timers.
     *
     * <p>Solange der Dialog sichtbar ist, wird die Hintergrund-UI gesperrt,
     * damit keine versehentlichen Mehrfachklicks den aktuellen Entwurf verlieren.</p>
     */
    private void showCreateTimerDialog() {
        if (dialogUiBlocked) {
            return;
        }
        setDialogUiBlocked(true);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_new_timer, null, false);
        TextInputEditText nameInput = dialogView.findViewById(R.id.dialogInputTimerName);
        TextInputEditText minutesInput = dialogView.findViewById(R.id.dialogInputMinutes);
        TextInputEditText secondsInput = dialogView.findViewById(R.id.dialogInputSeconds);
        RadioGroup alarmFrequencyGroup = dialogView.findViewById(R.id.dialogAlarmFrequencyGroup);
        com.google.android.material.slider.Slider alarmVolumeSlider = dialogView.findViewById(R.id.dialogAlarmVolumeSlider);
        TextView alarmVolumeValue = dialogView.findViewById(R.id.dialogAlarmVolumeValue);
        MaterialButton presetThirty = dialogView.findViewById(R.id.dialogPresetThirtySeconds);
        MaterialButton presetTwoMinutes = dialogView.findViewById(R.id.dialogPresetTwoMinutes);
        MaterialButton presetFiveMinutes = dialogView.findViewById(R.id.dialogPresetFiveMinutes);

        selectAnnouncementFrequency(alarmFrequencyGroup, ANNOUNCE_EVERY_FIVE_SECONDS);

        // Update volume display when slider changes
        alarmVolumeSlider.addOnChangeListener((slider, value, fromUser) -> {
            alarmVolumeValue.setText((int) value + "%");
        });

        presetThirty.setOnClickListener(v -> applyPreset(minutesInput, secondsInput, 0, 30));
        presetTwoMinutes.setOnClickListener(v -> applyPreset(minutesInput, secondsInput, 2, 0));
        presetFiveMinutes.setOnClickListener(v -> applyPreset(minutesInput, secondsInput, 5, 0));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_new_timer_title)
                .setView(dialogView)
                .setNegativeButton(R.string.action_cancel, (dialogInterface, which) -> dialogInterface.dismiss())
                .setPositiveButton(R.string.action_create_timer, null)
                .create();

        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(dialogInterface -> setDialogUiBlocked(false));

        dialog.setOnShowListener(dialogInterface -> {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setOnClickListener(v -> {
                String timerName = getText(nameInput);
                int minutes = parseNumber(minutesInput);
                int seconds = parseNumber(secondsInput);
                long announcementIntervalMillis = getSelectedAnnouncementFrequency(alarmFrequencyGroup);
                int alarmVolume = (int) alarmVolumeSlider.getValue();
                long durationMillis = ((minutes * 60L) + seconds) * 1000L;

                if (timerName.isEmpty()) {
                    nameInput.setError(getString(R.string.error_name_required));
                    nameInput.requestFocus();
                    return;
                }

                if (durationMillis <= 0L) {
                    secondsInput.setError(getString(R.string.error_duration_required));
                    secondsInput.requestFocus();
                    return;
                }

                nameInput.setError(null);
                secondsInput.setError(null);
                startTimer(timerName, minutes, seconds, announcementIntervalMillis, alarmVolume);
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    /**
     * Laedt einen sortierten Snapshot aus dem Service und aktualisiert die Listenansicht.
     */
    private void refreshTimers() {
        List<ManagedTimer> timers = TimerService.getTimersSnapshot();
        adapter.submitList(timers);
        boolean hasTimers = !timers.isEmpty();
        emptyStateView.setVisibility(hasTimers ? View.GONE : View.VISIBLE);
        emptyStateView.setText(getString(R.string.empty_state));
        updateTimerListHeight(timers.size());
    }

    /**
     * Zeigt die kurze In-App-Info zur App an.
     */
    private void showInfoDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hero_title)
                .setMessage(getString(R.string.info_dialog_message, getString(R.string.hero_badge), getString(R.string.hero_subtitle)))
                .setPositiveButton(R.string.action_close, (dialog, which) -> dialog.dismiss())
                .show();
    }

    /**
     * Bestaetigungsdialog zum Abbrechen eines laufenden Timers.
        *
        * @param timer der abzubrechende Timer
     */
    private void showCancelTimerDialog(ManagedTimer timer) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_cancel_timer_title)
                .setMessage(getString(R.string.dialog_cancel_timer_message, timer.getName()))
                .setNegativeButton(R.string.action_keep_running, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.action_cancel_timer, (dialog, which) -> {
                    TimerService.enqueueCancelTimer(this, timer.getId());
                    refreshTimers();
                })
                .show();
    }

    /**
     * Bestaetigungsdialog zum Loeschen eines terminalen Timers.
        *
        * @param timer der zu loeschende Timer
     */
    private void showDeleteTimerDialog(ManagedTimer timer) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_delete_timer_title)
                .setMessage(getString(R.string.dialog_delete_timer_message, timer.getName()))
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.action_delete_timer, (dialog, which) -> {
                    TimerService.enqueueDismissTimer(this, timer.getId());
                    refreshTimers();
                })
                .show();
    }

    /**
     * Oeffnet den Bearbeitungsdialog fuer einen bestehenden Timer.
     *
     * @param timer der zu bearbeitende Timer
     */
    private void showEditTimerDialog(ManagedTimer timer) {
        if (dialogUiBlocked) {
            return;
        }
        setDialogUiBlocked(true);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_new_timer, null, false);
        TextInputEditText nameInput = dialogView.findViewById(R.id.dialogInputTimerName);
        TextInputEditText minutesInput = dialogView.findViewById(R.id.dialogInputMinutes);
        TextInputEditText secondsInput = dialogView.findViewById(R.id.dialogInputSeconds);
        RadioGroup alarmFrequencyGroup = dialogView.findViewById(R.id.dialogAlarmFrequencyGroup);
        com.google.android.material.slider.Slider alarmVolumeSlider = dialogView.findViewById(R.id.dialogAlarmVolumeSlider);
        TextView alarmVolumeValue = dialogView.findViewById(R.id.dialogAlarmVolumeValue);
        MaterialButton presetThirty = dialogView.findViewById(R.id.dialogPresetThirtySeconds);
        MaterialButton presetTwoMinutes = dialogView.findViewById(R.id.dialogPresetTwoMinutes);
        MaterialButton presetFiveMinutes = dialogView.findViewById(R.id.dialogPresetFiveMinutes);

        long totalSeconds = timer.getDurationMillis() / 1000L;
        nameInput.setText(timer.getName());
        minutesInput.setText(String.valueOf(totalSeconds / 60));
        secondsInput.setText(String.valueOf(totalSeconds % 60));
        selectAnnouncementFrequency(alarmFrequencyGroup, timer.getAnnouncementIntervalMillis());
        alarmVolumeSlider.setValue(timer.getAlarmVolume());
        alarmVolumeValue.setText(timer.getAlarmVolume() + "%");

        // Update volume display when slider changes
        alarmVolumeSlider.addOnChangeListener((slider, value, fromUser) -> {
            alarmVolumeValue.setText((int) value + "%");
        });

        presetThirty.setOnClickListener(v -> applyPreset(minutesInput, secondsInput, 0, 30));
        presetTwoMinutes.setOnClickListener(v -> applyPreset(minutesInput, secondsInput, 2, 0));
        presetFiveMinutes.setOnClickListener(v -> applyPreset(minutesInput, secondsInput, 5, 0));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_edit_timer_title)
                .setView(dialogView)
                .setNegativeButton(R.string.action_cancel, (dialogInterface, which) -> dialogInterface.dismiss())
                .setPositiveButton(R.string.action_save_timer, null)
                .create();

        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(dialogInterface -> setDialogUiBlocked(false));

        dialog.setOnShowListener(dialogInterface -> {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setOnClickListener(v -> {
                String timerName = getText(nameInput);
                int minutes = parseNumber(minutesInput);
                int seconds = parseNumber(secondsInput);
                long announcementIntervalMillis = getSelectedAnnouncementFrequency(alarmFrequencyGroup);
                int alarmVolume = (int) alarmVolumeSlider.getValue();
                long durationMillis = ((minutes * 60L) + seconds) * 1000L;

                if (timerName.isEmpty()) {
                    nameInput.setError(getString(R.string.error_name_required));
                    nameInput.requestFocus();
                    return;
                }

                if (durationMillis <= 0L) {
                    secondsInput.setError(getString(R.string.error_duration_required));
                    secondsInput.requestFocus();
                    return;
                }

                nameInput.setError(null);
                secondsInput.setError(null);
                TimerService.enqueueReplaceTimer(this, timer.getId(), timerName, durationMillis, announcementIntervalMillis, alarmVolume);
                refreshTimers();
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    /**
     * Schaltet die Bedienung des Hintergrunds waehrend eines offenen Dialogs ein oder aus.
     *
     * @param blocked {@code true}, wenn nur der Dialog bedienbar sein soll
     */
    private void setDialogUiBlocked(boolean blocked) {
        dialogUiBlocked = blocked;
        uiInteractionBlocker.setVisibility(blocked ? View.VISIBLE : View.GONE);
        uiInteractionBlocker.setClickable(blocked);
        newTimerButton.setEnabled(!blocked);
        infoButton.setEnabled(!blocked);
        emptyStateView.setEnabled(!blocked);
        recyclerView.setEnabled(!blocked);
    }

    /**
     * Fordert unter Android 13+ die Laufzeitberechtigung fuer Notifications an.
     */
    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /**
     * Liefert getrimmten Text aus einem Eingabefeld oder einen Leerstring.
        *
        * @param editText Eingabefeld
        * @return getrimmter Inhalt oder leerer String
     */
    private String getText(TextInputEditText editText) {
        if (editText.getText() == null) {
            return "";
        }
        return editText.getText().toString().trim();
    }

    /**
     * Parsed eine Zahl aus einem Eingabefeld robust mit Fallback auf 0.
        *
        * @param editText Eingabefeld mit numerischem Inhalt
        * @return geparster Integer oder 0 bei leerem/ungueltigem Inhalt
     */
    private int parseNumber(TextInputEditText editText) {
        String value = getText(editText);
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private long getSelectedAnnouncementFrequency(RadioGroup alarmFrequencyGroup) {
        int checkedId = alarmFrequencyGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.dialogAlarmFrequencySingle) {
            return ANNOUNCE_SINGLE;
        }
        if (checkedId == R.id.dialogAlarmFrequencyEverySecond) {
            return ANNOUNCE_EVERY_SECOND;
        }
        if (checkedId == R.id.dialogAlarmFrequencyEveryThreeSeconds) {
            return ANNOUNCE_EVERY_THREE_SECONDS;
        }
        return ANNOUNCE_EVERY_FIVE_SECONDS;
    }

    private void selectAnnouncementFrequency(RadioGroup alarmFrequencyGroup, long intervalMillis) {
        if (intervalMillis <= ANNOUNCE_SINGLE) {
            alarmFrequencyGroup.check(R.id.dialogAlarmFrequencySingle);
            return;
        }
        if (intervalMillis == ANNOUNCE_EVERY_SECOND) {
            alarmFrequencyGroup.check(R.id.dialogAlarmFrequencyEverySecond);
            return;
        }
        if (intervalMillis == ANNOUNCE_EVERY_THREE_SECONDS) {
            alarmFrequencyGroup.check(R.id.dialogAlarmFrequencyEveryThreeSeconds);
            return;
        }
        alarmFrequencyGroup.check(R.id.dialogAlarmFrequencyEveryFiveSeconds);
    }

    /**
     * Stellt sicher, dass die Liste in ConstraintLayout als "match constraints" laeuft.
     *
     * @param timerCount aktuelle Anzahl der Timer in der Liste
     */
    private void updateTimerListHeight(int timerCount) {
        ViewGroup.LayoutParams layoutParams = recyclerView.getLayoutParams();
        if (layoutParams.height != 0) {
            // In ConstraintLayout, height=0 means "match constraints" and keeps items aligned to top.
            layoutParams.height = 0;
            recyclerView.setLayoutParams(layoutParams);
        }
    }
}