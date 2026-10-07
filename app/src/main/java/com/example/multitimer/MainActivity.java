package com.example.multitimer;

import android.animation.ObjectAnimator;
import android.Manifest;
import android.text.Editable;
import android.text.TextWatcher;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final int SORT_ALPHA = 1;
    private static final int SORT_RECENT_STARTED = 2;
    private static final int SORT_REMAINING = 3;
    private static final int SORT_MOST_USED = 4;
    private static final int SORT_RECENT_CREATED = 5;
    private static final int SORT_TIMER_TYPE = 6;
    private static final int MENU_INFO = 101;
    private static final int MENU_SETTINGS = 102;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshTimers();
            uiHandler.postDelayed(this, 1000L);
        }
    };

    private FloatingActionButton newTimerButton;
    private ImageButton sortButton;
    private TabLayout timerTabs;
    private TextView runningTimersTabLabel;
    private TextView runningTimersCountBadge;
    private ObjectAnimator runningTimersCountAnimator;
    private ImageButton infoButton;
    private TextView emptyStateView;
    private TextView timerStartedBanner;
    private View uiInteractionBlocker;
    private boolean dialogUiBlocked;
    private boolean awaitingInitialLanguageSelection;
    private final Runnable hideBannerRunnable = () -> timerStartedBanner.setVisibility(View.GONE);
    private RecyclerView recyclerView;
    private TimerAdapter adapter;
    private final ArrayDeque<Long> pendingGroupStepConfirmationIds = new ArrayDeque<>();
    private final Map<Long, Integer> queuedGroupStepConfirmationIds = new HashMap<>();
    private AlertDialog groupStepConfirmationDialog;
    private long activeGroupStepConfirmationId = -1L;
    private int activeGroupStepConfirmationIndex = -1;
    private int runningSort = SORT_REMAINING;
    private int savedSort = SORT_MOST_USED;
    private boolean runningSortAscending = true;
    private boolean savedSortAscending = false;
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
        sortButton = findViewById(R.id.sortButton);
        timerTabs = findViewById(R.id.timerTabs);
        View runningTabView = LayoutInflater.from(this).inflate(R.layout.tab_running_timers, timerTabs, false);
        runningTimersTabLabel = runningTabView.findViewById(R.id.runningTimersTabLabel);
        runningTimersCountBadge = runningTabView.findViewById(R.id.runningTimersCountBadge);
        infoButton = findViewById(R.id.infoButton);
        emptyStateView = findViewById(R.id.emptyState);
        timerStartedBanner = findViewById(R.id.timerStartedBanner);
        uiInteractionBlocker = findViewById(R.id.uiInteractionBlocker);
        recyclerView = findViewById(R.id.timerRecyclerView);

        timerTabs.addTab(timerTabs.newTab().setCustomView(runningTabView));
        timerTabs.addTab(timerTabs.newTab().setText(R.string.tab_saved_timers));
        long now = System.currentTimeMillis();
        boolean hasRunningTimer = false;
        for (ManagedTimer timer : TimerService.getTimersSnapshot()) {
            if (timer.isRunning(now)) {
                hasRunningTimer = true;
                break;
            }
        }
        timerTabs.selectTab(timerTabs.getTabAt(hasRunningTimer ? 0 : 1), false);
        updateTabLabelColors();
        timerTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
            updateTabLabelColors();
                refreshTimers();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        sortButton.setOnClickListener(this::showSortMenu);

        adapter = new TimerAdapter(new TimerAdapter.OnTimerActionListener() {
            @Override
            public void onStartSavedTimer(SavedTimer timer) {
                TimerService.enqueueStartSavedTimer(MainActivity.this, timer.getId());
                refreshTimers();
                showStartedBanner(timer.getName());
            }

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
            public void onDeleteSavedTimer(SavedTimer timer) {
                showDeleteSavedTimerDialog(timer);
            }

            @Override
            public void onDismissTimer(ManagedTimer timer) {
                TimerService.enqueueDismissTimer(MainActivity.this, timer.getId());
                refreshTimers();
            }

            @Override
            public void onDismissNotification(ManagedTimer timer) {
                TimerService.enqueueDismissNotification(MainActivity.this, timer.getId());
                refreshTimers();
            }

            @Override
            public void onEditSavedTimer(SavedTimer timer) {
                showEditTimerDialog(timer);
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        infoButton.setOnClickListener(this::showSettingsMenu);
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

        if (AppLanguageSettings.hasSelectedLanguage(this)) {
            maybeRequestNotificationPermission();
            refreshTimers();
        } else {
            awaitingInitialLanguageSelection = true;
            showInitialLanguageDialog();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (awaitingInitialLanguageSelection) {
            return;
        }
        TimerService.ensureServiceRunningForActiveTimers(this);
        uiHandler.post(refreshRunnable);
    }

    @Override
    protected void onStop() {
        uiHandler.removeCallbacks(refreshRunnable);
        stopRunningTimerCountAnimation();
        super.onStop();
    }

    /**
     * Erstellt einen neuen Timer aus Dialogwerten und zeigt ein kurzes Feedback-Banner.
        *
        * @param timerName Name des neuen Timers
        * @param minutes Minutenanteil der Dauer
        * @param seconds Sekundenanteil der Dauer
     */
    private void startTimer(String timerName, long durationMillis, long announcementIntervalMillis, int alarmVolume,
                    String completionText, TimerType timerType, int repeatCount,
                    boolean waitForIntervalConfirmation, List<TimerStep> steps) {
        TimerService.enqueueCreateTimer(this, timerName, durationMillis, announcementIntervalMillis, alarmVolume,
            completionText, timerType, repeatCount, waitForIntervalConfirmation, steps);
        timerTabs.selectTab(timerTabs.getTabAt(1));
        refreshTimers();
        uiHandler.removeCallbacks(hideBannerRunnable);
        timerStartedBanner.setText(getString(R.string.timer_saved_banner, timerName));
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
        timerStartedBanner.setText(getString(R.string.timer_started_banner, timerName));
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
    private void applyPreset(TextInputEditText hoursInput, TextInputEditText minutesInput, TextInputEditText secondsInput,
                             int hours, int minutes, int seconds) {
        hoursInput.setText(String.valueOf(hours));
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
        showTimerEditorDialog(null);
    }

    private void showEditTimerDialog(SavedTimer timer) {
        showTimerEditorDialog(timer);
    }

    private void showTimerEditorDialog(SavedTimer savedTimer) {
        if (dialogUiBlocked) {
            return;
        }
        setDialogUiBlocked(true);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_new_timer, null, false);
        TextInputEditText nameInput = dialogView.findViewById(R.id.dialogInputTimerName);
        TextInputEditText completionTextInput = dialogView.findViewById(R.id.dialogInputCompletionText);
        TextView completionTextLabel = dialogView.findViewById(R.id.dialogCompletionTextLabel);
        TextInputEditText hoursInput = dialogView.findViewById(R.id.dialogInputHours);
        TextInputEditText minutesInput = dialogView.findViewById(R.id.dialogInputMinutes);
        TextInputEditText secondsInput = dialogView.findViewById(R.id.dialogInputSeconds);
        TextInputEditText repeatCountInput = dialogView.findViewById(R.id.dialogRepeatCount);
        com.google.android.material.switchmaterial.SwitchMaterial intervalConfirmationSwitch =
            dialogView.findViewById(R.id.dialogWaitForIntervalConfirmation);
        Spinner timerTypeSpinner = dialogView.findViewById(R.id.dialogTimerType);
        View completionTextSection = dialogView.findViewById(R.id.dialogCompletionTextSection);
        View durationSection = dialogView.findViewById(R.id.dialogDurationSection);
        View presetSection = dialogView.findViewById(R.id.dialogPresetSection);
        View repeatCountSection = dialogView.findViewById(R.id.dialogRepeatCountSection);
        View groupSection = dialogView.findViewById(R.id.dialogGroupSection);
        View alarmFrequencyLabel = dialogView.findViewById(R.id.dialogAlarmFrequencyLabel);
        View alarmFrequencySection = dialogView.findViewById(R.id.dialogAlarmFrequencySection);
        LinearLayout groupStepsContainer = dialogView.findViewById(R.id.dialogGroupSteps);
        MaterialButton addGroupStepButton = dialogView.findViewById(R.id.dialogAddGroupStep);
        RadioGroup alarmFrequencyGroup = dialogView.findViewById(R.id.dialogAlarmFrequencyGroup);
        if (savedTimer != null) {
            alarmFrequencyGroup.setWeightSum(3f);
            dialogView.findViewById(R.id.dialogAlarmFrequencySingle).setVisibility(View.GONE);
        }
        com.google.android.material.slider.Slider alarmVolumeSlider = dialogView.findViewById(R.id.dialogAlarmVolumeSlider);
        TextView alarmVolumeValue = dialogView.findViewById(R.id.dialogAlarmVolumeValue);
        MaterialButton presetThirty = dialogView.findViewById(R.id.dialogPresetThirtySeconds);
        MaterialButton presetTwoMinutes = dialogView.findViewById(R.id.dialogPresetTwoMinutes);
        MaterialButton presetFiveMinutes = dialogView.findViewById(R.id.dialogPresetFiveMinutes);
        List<GroupStepEditor> stepEditors = new ArrayList<>();

        TimerType initialType = savedTimer == null ? TimerType.STANDARD : savedTimer.getTimerType();
        timerTypeSpinner.setSelection(initialType.ordinal());
        selectAnnouncementFrequency(alarmFrequencyGroup, savedTimer == null
                ? ANNOUNCE_EVERY_FIVE_SECONDS
                : savedTimer.getAnnouncementIntervalMillis());
        if (savedTimer != null) {
            nameInput.setText(savedTimer.getName());
            completionTextInput.setText(savedTimer.getCompletionText());
            if (getText(completionTextInput).isEmpty()) {
                completionTextInput.setText(defaultCompletionText(savedTimer.getName()));
            }
            repeatCountInput.setText(String.valueOf(savedTimer.getRepeatCount()));
            intervalConfirmationSwitch.setChecked(savedTimer.waitsForIntervalConfirmation());
            alarmVolumeSlider.setValue(savedTimer.getAlarmVolume());
            alarmVolumeValue.setText(savedTimer.getAlarmVolume() + "%");
            if (savedTimer.getTimerType() != TimerType.GROUP) {
                long totalSeconds = savedTimer.getDurationMillis() / 1000L;
                hoursInput.setText(String.valueOf(totalSeconds / 3600L));
                minutesInput.setText(String.valueOf((totalSeconds % 3600L) / 60L));
                secondsInput.setText(String.valueOf(totalSeconds % 60L));
            }
            for (TimerStep step : savedTimer.getSteps()) {
                addGroupStepEditor(groupStepsContainer, stepEditors, step);
            }
        }
        while (stepEditors.size() < 2) {
            addGroupStepEditor(groupStepsContainer, stepEditors, defaultGroupStep(stepEditors.size()));
        }
        bindCompletionTextToName(nameInput, completionTextInput);

        Runnable updateTypeSections = () -> {
            TimerType type = TimerType.values()[timerTypeSpinner.getSelectedItemPosition()];
            boolean group = type == TimerType.GROUP;
            completionTextSection.setVisibility(View.VISIBLE);
            completionTextLabel.setText(group ? R.string.label_group_completion_text : R.string.label_completion_text);
            durationSection.setVisibility(group ? View.GONE : View.VISIBLE);
            presetSection.setVisibility(group ? View.GONE : View.VISIBLE);
            repeatCountSection.setVisibility(type == TimerType.INTERVAL ? View.VISIBLE : View.GONE);
            intervalConfirmationSwitch.setVisibility(type == TimerType.INTERVAL ? View.VISIBLE : View.GONE);
            groupSection.setVisibility(group ? View.VISIBLE : View.GONE);
            alarmFrequencyLabel.setVisibility(View.VISIBLE);
            alarmFrequencySection.setVisibility(View.VISIBLE);
        };
        timerTypeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateTypeSections.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        updateTypeSections.run();
        addGroupStepButton.setOnClickListener(v -> {
            addGroupStepEditor(groupStepsContainer, stepEditors, defaultGroupStep(stepEditors.size()));
            updateGroupStepEditors(stepEditors, groupStepsContainer);
        });

        alarmVolumeSlider.addOnChangeListener((slider, value, fromUser) -> {
            alarmVolumeValue.setText((int) value + "%");
        });
        presetThirty.setOnClickListener(v -> applyPreset(hoursInput, minutesInput, secondsInput, 0, 0, 30));
        presetTwoMinutes.setOnClickListener(v -> applyPreset(hoursInput, minutesInput, secondsInput, 0, 2, 0));
        presetFiveMinutes.setOnClickListener(v -> applyPreset(hoursInput, minutesInput, secondsInput, 0, 5, 0));

        boolean editing = savedTimer != null;
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(editing ? R.string.dialog_edit_timer_title : R.string.dialog_new_timer_title)
                .setView(dialogView)
                .setNegativeButton(R.string.action_cancel, (dialogInterface, which) -> dialogInterface.dismiss())
                .setPositiveButton(editing ? R.string.action_save_timer : R.string.action_create_timer, null)
                .create();

        dialog.setCanceledOnTouchOutside(false);
            nameInput.requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.setOnDismissListener(dialogInterface -> setDialogUiBlocked(false));

        dialog.setOnShowListener(dialogInterface -> {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setOnClickListener(v -> {
                String timerName = getText(nameInput);
                TimerType timerType = TimerType.values()[timerTypeSpinner.getSelectedItemPosition()];
                long announcementIntervalMillis = getSelectedAnnouncementFrequency(alarmFrequencyGroup);
                int alarmVolume = (int) alarmVolumeSlider.getValue();
                long durationMillis;
                int repeatCount = 0;
                boolean waitForIntervalConfirmation = false;
                List<TimerStep> steps = new ArrayList<>();

                if (timerName.isEmpty()) {
                    nameInput.setError(getString(R.string.error_name_required));
                    nameInput.requestFocus();
                    return;
                }

                if (timerType == TimerType.GROUP) {
                    if (stepEditors.size() < 2) {
                        new MaterialAlertDialogBuilder(this)
                                .setMessage(R.string.error_group_steps_required)
                                .setPositiveButton(R.string.action_close, null)
                                .show();
                        return;
                    }
                    durationMillis = 0L;
                    for (GroupStepEditor editor : stepEditors) {
                        TimerStep step = editor.readStep(this);
                        if (step == null) {
                            return;
                        }
                        steps.add(step);
                        durationMillis += step.getDurationMillis();
                    }
                } else {
                    int hours = parseNumber(hoursInput);
                    int minutes = parseNumber(minutesInput);
                    int seconds = parseNumber(secondsInput);
                    if (minutes >= 60) {
                        minutesInput.setError(getString(R.string.error_minutes_range));
                        minutesInput.requestFocus();
                        return;
                    }
                    if (seconds >= 60) {
                        secondsInput.setError(getString(R.string.error_seconds_range));
                        secondsInput.requestFocus();
                        return;
                    }
                    durationMillis = ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L;
                    if (durationMillis <= 0L) {
                        secondsInput.setError(getString(R.string.error_duration_required));
                        secondsInput.requestFocus();
                        return;
                    }
                    if (timerType == TimerType.INTERVAL) {
                        waitForIntervalConfirmation = intervalConfirmationSwitch.isChecked();
                        String countText = getText(repeatCountInput);
                        try {
                            repeatCount = countText.isEmpty() ? 0 : Integer.parseInt(countText);
                        } catch (NumberFormatException exception) {
                            repeatCountInput.setError(getString(R.string.error_repeat_count_invalid));
                            repeatCountInput.requestFocus();
                            return;
                        }
                        if (repeatCount < 0) {
                            repeatCountInput.setError(getString(R.string.error_repeat_count_invalid));
                            repeatCountInput.requestFocus();
                            return;
                        }
                    }
                }

                nameInput.setError(null);
                if (editing) {
                    TimerService.enqueueReplaceTimer(this, savedTimer.getId(), timerName, durationMillis,
                            announcementIntervalMillis, alarmVolume, getText(completionTextInput),
                            timerType, repeatCount, waitForIntervalConfirmation, steps);
                    refreshTimers();
                } else {
                    startTimer(timerName, durationMillis, announcementIntervalMillis, alarmVolume,
                            getText(completionTextInput), timerType, repeatCount, waitForIntervalConfirmation, steps);
                }
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private TimerStep defaultGroupStep(int index) {
        String stepName = getString(R.string.default_group_step_name, index + 1);
        return new TimerStep(stepName, 60000L, defaultCompletionText(stepName));
    }

    private void addGroupStepEditor(LinearLayout container, List<GroupStepEditor> editors, TimerStep step) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_timer_group_step, container, false);
        GroupStepEditor editor = new GroupStepEditor(row);
        editor.nameInput.setText(step.getName());
        long seconds = step.getDurationMillis() / 1000L;
        editor.hoursInput.setText(String.valueOf(seconds / 3600L));
        editor.minutesInput.setText(String.valueOf((seconds % 3600L) / 60L));
        editor.secondsInput.setText(String.valueOf(seconds % 60L));
        editor.completionTextInput.setText(step.getCompletionText());
        if (getText(editor.completionTextInput).isEmpty()) {
            editor.completionTextInput.setText(defaultCompletionText(step.getName()));
        }
        bindCompletionTextToName(editor.nameInput, editor.completionTextInput);
        editors.add(editor);
        container.addView(row);
        editor.upButton.setOnClickListener(v -> moveGroupStep(editors, container, editor, -1));
        editor.downButton.setOnClickListener(v -> moveGroupStep(editors, container, editor, 1));
        editor.removeButton.setOnClickListener(v -> {
            if (editors.size() > 2) {
                editors.remove(editor);
                container.removeView(row);
                updateGroupStepEditors(editors, container);
            }
        });
        updateGroupStepEditors(editors, container);
    }

    private String defaultCompletionText(String timerName) {
        String normalizedName = timerName == null ? "" : timerName.trim();
        return normalizedName.isEmpty() ? "" : normalizedName + " " + getString(R.string.tts_completed);
    }

    private void bindCompletionTextToName(TextInputEditText nameInput, TextInputEditText completionTextInput) {
        String initialName = getText(nameInput);
        String initialCompletionText = getText(completionTextInput);
        boolean[] followsName = {initialCompletionText.isEmpty()
                || initialCompletionText.equals(defaultCompletionText(initialName))};
        boolean[] updatingCompletionText = {false};

        completionTextInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (!updatingCompletionText[0]) {
                    followsName[0] = false;
                }
            }
        });
        nameInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (!followsName[0]) {
                    return;
                }
                String generatedText = defaultCompletionText(editable.toString());
                updatingCompletionText[0] = true;
                completionTextInput.setText(generatedText);
                completionTextInput.setSelection(generatedText.length());
                updatingCompletionText[0] = false;
            }
        });
    }

    private void moveGroupStep(List<GroupStepEditor> editors, LinearLayout container, GroupStepEditor editor, int offset) {
        int index = editors.indexOf(editor);
        int target = index + offset;
        if (target < 0 || target >= editors.size()) {
            return;
        }
        editors.remove(index);
        editors.add(target, editor);
        container.removeView(editor.root);
        container.addView(editor.root, target);
        updateGroupStepEditors(editors, container);
    }

    private void updateGroupStepEditors(List<GroupStepEditor> editors, LinearLayout container) {
        for (int index = 0; index < editors.size(); index++) {
            GroupStepEditor editor = editors.get(index);
            editor.titleView.setText(getString(R.string.label_group_step, index + 1));
            editor.upButton.setEnabled(index > 0);
            editor.downButton.setEnabled(index < editors.size() - 1);
            editor.removeButton.setEnabled(editors.size() > 2);
            editor.removeButton.setAlpha(editors.size() > 2 ? 1f : 0.35f);
        }
    }

    private static final class GroupStepEditor {
        final View root;
        final TextView titleView;
        final TextInputEditText nameInput;
        final TextInputEditText hoursInput;
        final TextInputEditText minutesInput;
        final TextInputEditText secondsInput;
        final TextInputEditText completionTextInput;
        final ImageButton upButton;
        final ImageButton downButton;
        final ImageButton removeButton;

        GroupStepEditor(View root) {
            this.root = root;
            titleView = root.findViewById(R.id.groupStepTitle);
            nameInput = root.findViewById(R.id.groupStepName);
            hoursInput = root.findViewById(R.id.groupStepHours);
            minutesInput = root.findViewById(R.id.groupStepMinutes);
            secondsInput = root.findViewById(R.id.groupStepSeconds);
            completionTextInput = root.findViewById(R.id.groupStepCompletionText);
            upButton = root.findViewById(R.id.groupStepUp);
            downButton = root.findViewById(R.id.groupStepDown);
            removeButton = root.findViewById(R.id.groupStepRemove);
        }

        TimerStep readStep(MainActivity activity) {
            String stepName = activity.getText(nameInput);
            int hours = activity.parseNumber(hoursInput);
            int minutes = activity.parseNumber(minutesInput);
            int seconds = activity.parseNumber(secondsInput);
            if (stepName.isEmpty()) {
                nameInput.setError(activity.getString(R.string.error_group_step_name_required));
                nameInput.requestFocus();
                return null;
            }
            if (minutes >= 60 || seconds >= 60) {
                if (minutes >= 60) {
                    minutesInput.setError(activity.getString(R.string.error_minutes_range));
                    minutesInput.requestFocus();
                } else {
                    secondsInput.setError(activity.getString(R.string.error_seconds_range));
                    secondsInput.requestFocus();
                }
                return null;
            }
            long duration = ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L;
            if (duration <= 0L) {
                secondsInput.setError(activity.getString(R.string.error_duration_required));
                secondsInput.requestFocus();
                return null;
            }
            return new TimerStep(stepName, duration, activity.getText(completionTextInput));
        }
    }

    /**
     * Laedt einen sortierten Snapshot aus dem Service und aktualisiert die Listenansicht.
     */
    private void refreshTimers() {
        boolean showingSavedTimers = timerTabs.getSelectedTabPosition() == 1;
        boolean ascending = showingSavedTimers ? savedSortAscending : runningSortAscending;
        sortButton.setImageResource(ascending ? R.drawable.ic_ui_sort_ascending : R.drawable.ic_ui_sort_descending);
        sortButton.setContentDescription(getString(getSortLabel(showingSavedTimers)) + ", "
            + getString(ascending ? R.string.sort_order_ascending : R.string.sort_order_descending));
        List<ManagedTimer> runningTimers = TimerService.getTimersSnapshot();
        long now = System.currentTimeMillis();
        int runningTimerCount = 0;
        for (ManagedTimer timer : runningTimers) {
            if (timer.isRunning(now)) {
                runningTimerCount++;
            }
        }
        updateRunningTimerCount(runningTimerCount);
        updateGroupStepConfirmationDialogs(runningTimers);
        if (showingSavedTimers) {
            List<SavedTimer> timers = TimerService.getSavedTimersSnapshot();
            sortSavedTimers(timers);
            adapter.submitSavedList(timers);
            emptyStateView.setText(R.string.empty_saved_timers);
        } else {
            sortRunningTimers(runningTimers);
            adapter.submitList(runningTimers);
            emptyStateView.setText(R.string.empty_running_timers);
        }
        boolean hasTimers = adapter.getItemCount() > 0;
        emptyStateView.setVisibility(hasTimers ? View.GONE : View.VISIBLE);
        updateTimerListHeight(adapter.getItemCount());
    }

    private void updateRunningTimerCount(int count) {
        TabLayout.Tab runningTab = timerTabs.getTabAt(0);
        if (runningTab != null) {
            runningTab.setContentDescription(getString(R.string.tab_running_timers_accessibility, count));
        }
        if (count == 0) {
            runningTimersCountBadge.setVisibility(View.GONE);
            runningTimersCountBadge.setText("");
            stopRunningTimerCountAnimation();
            return;
        }

        runningTimersCountBadge.setText(count > 99
                ? getString(R.string.running_timer_count_overflow)
                : String.valueOf(count));
        runningTimersCountBadge.setVisibility(View.VISIBLE);
        if (runningTimersCountAnimator == null || !runningTimersCountAnimator.isRunning()) {
            runningTimersCountAnimator = ObjectAnimator.ofFloat(runningTimersCountBadge, View.ALPHA, 1f, 0.35f);
            runningTimersCountAnimator.setDuration(650L);
            runningTimersCountAnimator.setRepeatMode(ObjectAnimator.REVERSE);
            runningTimersCountAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            runningTimersCountAnimator.setInterpolator(new LinearInterpolator());
            runningTimersCountAnimator.start();
        }
    }

    private void updateTabLabelColors() {
        boolean runningSelected = timerTabs.getSelectedTabPosition() == 0;
        runningTimersTabLabel.setTextColor(getColor(runningSelected ? R.color.textPrimary : R.color.textSecondary));
    }

    private void stopRunningTimerCountAnimation() {
        if (runningTimersCountAnimator != null) {
            runningTimersCountAnimator.cancel();
            runningTimersCountAnimator = null;
        }
        if (runningTimersCountBadge != null) {
            runningTimersCountBadge.setAlpha(1f);
        }
    }

    private void showSortMenu(View anchor) {
        boolean showingSavedTimers = timerTabs.getSelectedTabPosition() == 1;
        PopupMenu popup = new PopupMenu(this, anchor);
        if (showingSavedTimers) {
            popup.getMenu().add(0, SORT_MOST_USED, 0, R.string.sort_most_used);
            popup.getMenu().add(0, SORT_RECENT_CREATED, 1, R.string.sort_recent_created);
            popup.getMenu().add(0, SORT_ALPHA, 2, R.string.sort_alpha);
            popup.getMenu().add(0, SORT_TIMER_TYPE, 3, R.string.sort_timer_type);
        } else {
            popup.getMenu().add(0, SORT_ALPHA, 0, R.string.sort_alpha);
            popup.getMenu().add(0, SORT_RECENT_STARTED, 1, R.string.sort_recent_started);
            popup.getMenu().add(0, SORT_REMAINING, 2, R.string.sort_remaining);
            popup.getMenu().add(0, SORT_TIMER_TYPE, 3, R.string.sort_timer_type);
        }
        popup.setOnMenuItemClickListener(item -> {
            if (showingSavedTimers) {
                int selectedSort = item.getItemId();
                if (savedSort == selectedSort) {
                    savedSortAscending = !savedSortAscending;
                } else {
                    savedSort = selectedSort;
                    savedSortAscending = isDefaultAscending(selectedSort);
                }
            } else {
                int selectedSort = item.getItemId();
                if (runningSort == selectedSort) {
                    runningSortAscending = !runningSortAscending;
                } else {
                    runningSort = selectedSort;
                    runningSortAscending = isDefaultAscending(selectedSort);
                }
            }
            refreshTimers();
            return true;
        });
        popup.show();
    }

    private int getSortLabel(boolean savedTimers) {
        int sort = savedTimers ? savedSort : runningSort;
        if (sort == SORT_ALPHA) {
            return R.string.sort_label_alpha;
        }
        if (sort == SORT_RECENT_STARTED) {
            return R.string.sort_label_recent_started;
        }
        if (sort == SORT_MOST_USED) {
            return R.string.sort_label_most_used;
        }
        if (sort == SORT_RECENT_CREATED) {
            return R.string.sort_label_recent_created;
        }
        if (sort == SORT_TIMER_TYPE) {
            return R.string.sort_label_timer_type;
        }
        return R.string.sort_label_remaining;
    }

    private boolean isDefaultAscending(int sort) {
        return sort != SORT_RECENT_STARTED && sort != SORT_MOST_USED && sort != SORT_RECENT_CREATED;
    }

    private void sortRunningTimers(List<ManagedTimer> timers) {
        Comparator<ManagedTimer> comparator = (left, right) -> {
            if (runningSort == SORT_TIMER_TYPE) {
                int byType = left.getTimerType().compareTo(right.getTimerType());
                return byType != 0 ? byType : compareNames(left.getName(), right.getName());
            }
            if (runningSort == SORT_ALPHA) {
                return compareNames(left.getName(), right.getName());
            }
            if (runningSort == SORT_RECENT_STARTED) {
                return Long.compare(left.getStartedAtMillis(), right.getStartedAtMillis());
            }
            long now = System.currentTimeMillis();
            return Long.compare(left.getRemainingMillis(now), right.getRemainingMillis(now));
        };
        timers.sort(runningSortAscending ? comparator : comparator.reversed());
    }

    private void sortSavedTimers(List<SavedTimer> timers) {
        Comparator<SavedTimer> comparator = (left, right) -> {
            if (savedSort == SORT_TIMER_TYPE) {
                int byType = left.getTimerType().compareTo(right.getTimerType());
                return byType != 0 ? byType : compareNames(left.getName(), right.getName());
            }
            if (savedSort == SORT_RECENT_CREATED) {
                return Long.compare(left.getCreatedAtMillis(), right.getCreatedAtMillis());
            }
            if (savedSort == SORT_ALPHA) {
                return compareNames(left.getName(), right.getName());
            }
            int byUsage = Integer.compare(left.getUsageCount(), right.getUsageCount());
            if (byUsage != 0) {
                return byUsage;
            }
            return compareNames(left.getName(), right.getName());
        };
        timers.sort(savedSortAscending ? comparator : comparator.reversed());
    }

    private int compareNames(String left, String right) {
        return left.compareToIgnoreCase(right);
    }

    private void updateGroupStepConfirmationDialogs(List<ManagedTimer> timers) {
        Map<Long, Integer> awaitingPhases = new HashMap<>();
        for (ManagedTimer timer : timers) {
            if (timer.requiresPhaseConfirmation()) {
                int phaseIndex = getConfirmationPhaseIndex(timer);
                awaitingPhases.put(timer.getId(), phaseIndex);
                Integer queuedStep = queuedGroupStepConfirmationIds.get(timer.getId());
                if (queuedStep == null || queuedStep != phaseIndex) {
                    pendingGroupStepConfirmationIds.removeIf(id -> id == timer.getId());
                    queuedGroupStepConfirmationIds.put(timer.getId(), phaseIndex);
                    pendingGroupStepConfirmationIds.addLast(timer.getId());
                }
            }
        }

        pendingGroupStepConfirmationIds.removeIf(timerId -> !awaitingPhases.containsKey(timerId)
                || !queuedGroupStepConfirmationIds.get(timerId).equals(awaitingPhases.get(timerId)));
        queuedGroupStepConfirmationIds.entrySet().removeIf(entry -> !awaitingPhases.containsKey(entry.getKey())
                || !entry.getValue().equals(awaitingPhases.get(entry.getKey())));
        if (groupStepConfirmationDialog != null
                && !Integer.valueOf(activeGroupStepConfirmationIndex).equals(awaitingPhases.get(activeGroupStepConfirmationId))) {
            groupStepConfirmationDialog.dismiss();
        }
        showNextGroupStepConfirmationDialog();
    }

    private void showNextGroupStepConfirmationDialog() {
        if (dialogUiBlocked || groupStepConfirmationDialog != null) {
            return;
        }

        while (!pendingGroupStepConfirmationIds.isEmpty()) {
            long timerId = pendingGroupStepConfirmationIds.removeFirst();
            Integer requestedPhaseIndex = queuedGroupStepConfirmationIds.get(timerId);
            ManagedTimer timer = null;
            for (ManagedTimer candidate : TimerService.getTimersSnapshot()) {
                if (candidate.getId() == timerId && candidate.requiresPhaseConfirmation()
                        && requestedPhaseIndex != null
                        && getConfirmationPhaseIndex(candidate) == requestedPhaseIndex) {
                    timer = candidate;
                    break;
                }
            }
            if (timer == null) {
                queuedGroupStepConfirmationIds.remove(timerId, requestedPhaseIndex);
                continue;
            }

                ManagedTimer confirmingTimer = timer;
                boolean groupTimer = confirmingTimer.getTimerType() == TimerType.GROUP;
                String message = groupTimer
                    ? getString(R.string.group_step_confirmation_message,
                        confirmingTimer.getAnnouncementName(), confirmingTimer.getCurrentStepIndex() + 1,
                        confirmingTimer.getSteps().size())
                    : getString(R.string.interval_confirmation_message, getIntervalProgressLabel(confirmingTimer));
                int confirmLabel = groupTimer
                    ? (confirmingTimer.hasNextGroupStep()
                        ? R.string.action_confirm_next_group_step
                        : R.string.action_finish_timer_group)
                    : R.string.action_confirm_next_interval;
            AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(confirmingTimer.getName())
                    .setMessage(message)
                    .setNegativeButton(R.string.action_cancel_timer, (dialogInterface, which) ->
                        TimerService.enqueueCancelTimer(this, confirmingTimer.getId()))
                    .setNeutralButton(confirmingTimer.isPhaseAnnouncementMuted()
                            ? R.string.action_phase_announcement_muted
                            : R.string.action_mute_phase_announcement, null)
                    .setPositiveButton(confirmLabel,
                        (dialogInterface, which) -> TimerService.enqueueAcknowledgeTimerPhase(this, confirmingTimer.getId()))
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.setCancelable(false);
            activeGroupStepConfirmationId = timerId;
            activeGroupStepConfirmationIndex = requestedPhaseIndex;
            groupStepConfirmationDialog = dialog;
            dialog.setOnDismissListener(dialogInterface -> {
                if (activeGroupStepConfirmationId == timerId) {
                    activeGroupStepConfirmationId = -1L;
                    activeGroupStepConfirmationIndex = -1;
                    groupStepConfirmationDialog = null;
                }
                showNextGroupStepConfirmationDialog();
            });
            dialog.show();
            android.widget.Button muteButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (confirmingTimer.isPhaseAnnouncementMuted()) {
                muteButton.setEnabled(false);
            } else {
                muteButton.setOnClickListener(view -> {
                    TimerService.enqueueMutePhaseAnnouncement(this, confirmingTimer.getId());
                    muteButton.setText(R.string.action_phase_announcement_muted);
                    muteButton.setEnabled(false);
                });
            }
            return;
        }
    }

    private int getConfirmationPhaseIndex(ManagedTimer timer) {
        return timer.getTimerType() == TimerType.GROUP
                ? timer.getCurrentStepIndex()
                : timer.getCompletedCycles();
    }

    private String getIntervalProgressLabel(ManagedTimer timer) {
        if (timer.getRepeatCount() == 0) {
            return getString(R.string.interval_progress_unlimited, timer.getCompletedCycles());
        }
        return getString(R.string.interval_progress_finite, timer.getCompletedCycles(), timer.getRepeatCount());
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

    private void showSettingsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        MenuItem settingsItem = popup.getMenu().add(0, MENU_SETTINGS, 0, R.string.action_settings);
        settingsItem.setIcon(R.drawable.ic_ui_settings);
        MenuItem infoItem = popup.getMenu().add(0, MENU_INFO, 1, R.string.action_show_info);
        infoItem.setIcon(R.drawable.ic_ui_info);
        popup.setForceShowIcon(true);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_SETTINGS) {
                showAppSettingsDialog();
                return true;
            }
            if (item.getItemId() == MENU_INFO) {
                showInfoDialog();
                return true;
            }
            return false;
        });
        popup.show();
    }

        private void showAppSettingsDialog() {
        View settingsView = LayoutInflater.from(this).inflate(R.layout.dialog_app_settings, null, false);
            Spinner languageSpinner = settingsView.findViewById(R.id.settingLanguage);
            android.widget.ArrayAdapter<String> languageAdapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, AppLanguageSettings.getLanguageNames());
            languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            languageSpinner.setAdapter(languageAdapter);
            languageSpinner.setSelection(AppLanguageSettings.getSelectedIndex(this));
        com.google.android.material.switchmaterial.SwitchMaterial forceAlarmSoundSwitch =
            settingsView.findViewById(R.id.settingForceAlarmSound);
        forceAlarmSoundSwitch.setChecked(AppSettings.forceAlarmSoundEnabled(this));

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_app_settings_title)
            .setView(settingsView)
            .setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.action_save_settings, (dialog, which) -> {
                    AppSettings.setForceAlarmSoundEnabled(this, forceAlarmSoundSwitch.isChecked());
                    AppLanguageSettings.selectLanguage(this, languageSpinner.getSelectedItemPosition());
                })
            .show();
        }

    private void showInitialLanguageDialog() {
        String[] languageNames = AppLanguageSettings.getLanguageNames();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.language_selection_title)
                .setSingleChoiceItems(languageNames, AppLanguageSettings.getSelectedIndex(this), (dialog, which) -> {
                    AppLanguageSettings.selectLanguage(this, which);
                    awaitingInitialLanguageSelection = false;
                    dialog.dismiss();
                })
                .setCancelable(false)
                .create()
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
    private void showDeleteSavedTimerDialog(SavedTimer timer) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_delete_saved_timer_title)
                .setMessage(getString(R.string.dialog_delete_saved_timer_message, timer.getName()))
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.action_delete_timer, (dialog, which) -> {
                    TimerService.enqueueDeleteSavedTimer(this, timer.getId());
                    refreshTimers();
                })
                .show();
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
        if (!blocked) {
            showNextGroupStepConfirmationDialog();
        }
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