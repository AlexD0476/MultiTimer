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

public final class MainActivity extends AppCompatActivity {
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
        newTimerButton.setOnClickListener(v -> showCreateTimerDialog());
        emptyStateView.setOnClickListener(v -> showCreateTimerDialog());
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

    private void startTimer(String timerName, int minutes, int seconds) {
        long durationMillis = ((minutes * 60L) + seconds) * 1000L;
        TimerService.enqueueCreateTimer(this, timerName, durationMillis);
        refreshTimers();
        uiHandler.removeCallbacks(hideBannerRunnable);
        timerStartedBanner.setText(timerName + " angelegt");
        timerStartedBanner.setVisibility(View.VISIBLE);
        uiHandler.postDelayed(hideBannerRunnable, 2500L);
    }

    private void showStartedBanner(String timerName) {
        uiHandler.removeCallbacks(hideBannerRunnable);
        timerStartedBanner.setText(timerName + " gestartet");
        timerStartedBanner.setVisibility(View.VISIBLE);
        uiHandler.postDelayed(hideBannerRunnable, 3000L);
    }

    private void applyPreset(TextInputEditText minutesInput, TextInputEditText secondsInput, int minutes, int seconds) {
        minutesInput.setText(String.valueOf(minutes));
        secondsInput.setText(String.valueOf(seconds));
    }

    private void showCreateTimerDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_new_timer, null, false);
        TextInputEditText nameInput = dialogView.findViewById(R.id.dialogInputTimerName);
        TextInputEditText minutesInput = dialogView.findViewById(R.id.dialogInputMinutes);
        TextInputEditText secondsInput = dialogView.findViewById(R.id.dialogInputSeconds);
        MaterialButton presetThirty = dialogView.findViewById(R.id.dialogPresetThirtySeconds);
        MaterialButton presetTwoMinutes = dialogView.findViewById(R.id.dialogPresetTwoMinutes);
        MaterialButton presetFiveMinutes = dialogView.findViewById(R.id.dialogPresetFiveMinutes);

        presetThirty.setOnClickListener(v -> applyPreset(minutesInput, secondsInput, 0, 30));
        presetTwoMinutes.setOnClickListener(v -> applyPreset(minutesInput, secondsInput, 2, 0));
        presetFiveMinutes.setOnClickListener(v -> applyPreset(minutesInput, secondsInput, 5, 0));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_new_timer_title)
                .setView(dialogView)
                .setNegativeButton(R.string.action_cancel, (dialogInterface, which) -> dialogInterface.dismiss())
                .setPositiveButton(R.string.action_create_timer, null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setOnClickListener(v -> {
                String timerName = getText(nameInput);
                int minutes = parseNumber(minutesInput);
                int seconds = parseNumber(secondsInput);
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
                startTimer(timerName, minutes, seconds);
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private void refreshTimers() {
        List<ManagedTimer> timers = TimerService.getTimersSnapshot();
        adapter.submitList(timers);
        boolean hasTimers = !timers.isEmpty();
        emptyStateView.setVisibility(hasTimers ? View.GONE : View.VISIBLE);
        emptyStateView.setText(getString(R.string.empty_state));
        updateTimerListHeight(timers.size());
    }

    private void showInfoDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hero_title)
                .setMessage(getString(R.string.info_dialog_message, getString(R.string.hero_badge), getString(R.string.hero_subtitle)))
                .setPositiveButton(R.string.action_close, (dialog, which) -> dialog.dismiss())
                .show();
    }

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

    private void showEditTimerDialog(ManagedTimer timer) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_new_timer, null, false);
        TextInputEditText nameInput = dialogView.findViewById(R.id.dialogInputTimerName);
        TextInputEditText minutesInput = dialogView.findViewById(R.id.dialogInputMinutes);
        TextInputEditText secondsInput = dialogView.findViewById(R.id.dialogInputSeconds);
        MaterialButton presetThirty = dialogView.findViewById(R.id.dialogPresetThirtySeconds);
        MaterialButton presetTwoMinutes = dialogView.findViewById(R.id.dialogPresetTwoMinutes);
        MaterialButton presetFiveMinutes = dialogView.findViewById(R.id.dialogPresetFiveMinutes);

        long totalSeconds = timer.getDurationMillis() / 1000L;
        nameInput.setText(timer.getName());
        minutesInput.setText(String.valueOf(totalSeconds / 60));
        secondsInput.setText(String.valueOf(totalSeconds % 60));

        presetThirty.setOnClickListener(v -> applyPreset(minutesInput, secondsInput, 0, 30));
        presetTwoMinutes.setOnClickListener(v -> applyPreset(minutesInput, secondsInput, 2, 0));
        presetFiveMinutes.setOnClickListener(v -> applyPreset(minutesInput, secondsInput, 5, 0));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_edit_timer_title)
                .setView(dialogView)
                .setNegativeButton(R.string.action_cancel, (dialogInterface, which) -> dialogInterface.dismiss())
                .setPositiveButton(R.string.action_save_timer, null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setOnClickListener(v -> {
                String timerName = getText(nameInput);
                int minutes = parseNumber(minutesInput);
                int seconds = parseNumber(secondsInput);
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
                TimerService.enqueueReplaceTimer(this, timer.getId(), timerName, durationMillis);
                refreshTimers();
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private String getText(TextInputEditText editText) {
        if (editText.getText() == null) {
            return "";
        }
        return editText.getText().toString().trim();
    }

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

    private void updateTimerListHeight(int timerCount) {
        ViewGroup.LayoutParams layoutParams = recyclerView.getLayoutParams();
        if (layoutParams.height != 0) {
            // In ConstraintLayout, height=0 means "match constraints" and keeps items aligned to top.
            layoutParams.height = 0;
            recyclerView.setLayoutParams(layoutParams);
        }
    }
}