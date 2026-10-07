package com.example.multitimer;

import android.animation.ObjectAnimator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView-Adapter fuer die Darstellung und Bedienung einzelner Timerkarten.
 */
final class TimerAdapter extends RecyclerView.Adapter<TimerAdapter.TimerViewHolder> {
    /**
     * Callback-Schnittstelle fuer Nutzeraktionen auf einer Timerkarte.
     */
    interface OnTimerActionListener {
        void onStartSavedTimer(SavedTimer timer);

        void onRestartTimer(ManagedTimer timer);

        void onCancelTimer(ManagedTimer timer);

        void onDeleteSavedTimer(SavedTimer timer);

        void onDismissTimer(ManagedTimer timer);

        void onDismissNotification(ManagedTimer timer);

        void onEditSavedTimer(SavedTimer timer);
    }

    private final List<ManagedTimer> timers = new ArrayList<>();
    private final List<SavedTimer> savedTimers = new ArrayList<>();
    private final OnTimerActionListener actionListener;
    private boolean showingSavedTimers;

    TimerAdapter(OnTimerActionListener actionListener) {
        this.actionListener = actionListener;
    }

    /**
     * Ersetzt die angezeigte Liste mit einem aktuellen Snapshot aus dem Service.
     */
    void submitList(List<ManagedTimer> nextTimers) {
        showingSavedTimers = false;
        timers.clear();
        timers.addAll(nextTimers);
        notifyDataSetChanged();
    }

    void submitSavedList(List<SavedTimer> nextTimers) {
        showingSavedTimers = true;
        savedTimers.clear();
        savedTimers.addAll(nextTimers);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TimerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_timer, parent, false);
        return new TimerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TimerViewHolder holder, int position) {
        if (showingSavedTimers) {
            holder.bindSaved(savedTimers.get(position), actionListener);
        } else {
            holder.bindRunning(timers.get(position), actionListener);
        }
    }

    @Override
    public int getItemCount() {
        return showingSavedTimers ? savedTimers.size() : timers.size();
    }

    static final class TimerViewHolder extends RecyclerView.ViewHolder {
        private final TextView titleView;
        private final ImageView typeIconView;
        private final TextView statusView;
        private final TextView remainingView;
        private final TextView durationView;
        private final ImageButton actionButton;
        private final ImageButton notificationButton;
        private final ImageButton deleteButton;
        private ObjectAnimator blinkAnimator;

        TimerViewHolder(@NonNull View itemView) {
            super(itemView);
            titleView = itemView.findViewById(R.id.timerName);
            typeIconView = itemView.findViewById(R.id.timerTypeIcon);
            statusView = itemView.findViewById(R.id.timerStatus);
            remainingView = itemView.findViewById(R.id.timerRemaining);
            durationView = itemView.findViewById(R.id.timerDuration);
            actionButton = itemView.findViewById(R.id.timerActionButton);
            notificationButton = itemView.findViewById(R.id.timerNotificationButton);
            deleteButton = itemView.findViewById(R.id.timerDeleteButton);
        }

        void bindSaved(SavedTimer timer, OnTimerActionListener actionListener) {
            bindTypeIcon(timer.getTimerType());
            titleView.setText(timer.getName());
            titleView.setOnClickListener(v -> actionListener.onEditSavedTimer(timer));
            durationView.setText(itemView.getContext().getString(
                R.string.timer_duration_template,
                TimerFormatter.formatDuration(timer.getDurationMillis())
            ));
            if (timer.getTimerType() == TimerType.INTERVAL) {
                durationView.append(" · " + itemView.getContext().getString(R.string.timer_type_interval));
            } else if (timer.getTimerType() == TimerType.GROUP) {
                durationView.append(" · " + itemView.getContext().getString(R.string.timer_type_group, timer.getSteps().size()));
            }
            remainingView.setText("");
            remainingView.setVisibility(View.GONE);
            statusView.setText(R.string.timer_status_saved);
            statusView.setBackground(ContextCompat.getDrawable(itemView.getContext(), R.drawable.bg_status_chip_ready_blue));
            statusView.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.statusBlueStroke));
            statusView.setAlpha(1f);
            stopBlink();

            actionButton.setImageResource(R.drawable.ic_ui_play);
            actionButton.setContentDescription(itemView.getContext().getString(R.string.action_start_timer));
            actionButton.setAlpha(1f);
            actionButton.setEnabled(true);
            actionButton.setOnClickListener(v -> actionListener.onStartSavedTimer(timer));

            notificationButton.setVisibility(View.GONE);
            notificationButton.setEnabled(false);
            notificationButton.setOnClickListener(null);

            deleteButton.setVisibility(View.VISIBLE);
            deleteButton.setAlpha(1f);
            deleteButton.setEnabled(true);
            deleteButton.setOnClickListener(v -> actionListener.onDeleteSavedTimer(timer));
        }

        void bindRunning(ManagedTimer timer, OnTimerActionListener actionListener) {
            long now = System.currentTimeMillis();
            bindTypeIcon(timer.getTimerType());
            remainingView.setVisibility(View.VISIBLE);
            notificationButton.setVisibility(View.GONE);
            notificationButton.setEnabled(false);
            notificationButton.setOnClickListener(null);
            deleteButton.setVisibility(View.GONE);
            deleteButton.setEnabled(false);
            deleteButton.setOnClickListener(null);
            titleView.setText(timer.getName());
            titleView.setOnClickListener(null);
            durationView.setText(itemView.getContext().getString(
                    R.string.timer_duration_template,
                    TimerFormatter.formatDuration(timer.getDurationMillis())
            ));
            if (timer.getTimerType() == TimerType.INTERVAL) {
                durationView.append(" · " + getIntervalProgressLabel(timer));
            } else if (timer.getTimerType() == TimerType.GROUP) {
                durationView.append(" · " + timer.getProgressLabel());
            }

            if (timer.isCompleted()) {
                statusView.setText(timer.isCompleted()
                        ? R.string.timer_status_completed
                        : R.string.timer_status_cancelled);
                statusView.setBackground(ContextCompat.getDrawable(itemView.getContext(),
                        timer.isCompleted()
                                ? R.drawable.bg_status_chip_completed
                                : R.drawable.bg_status_chip_cancelled));
                statusView.setTextColor(ContextCompat.getColor(itemView.getContext(),
                        timer.isCompleted() ? R.color.accentPrimaryDark : R.color.statusRedStroke));
                remainingView.setText(R.string.timer_run_completed);
                actionButton.setImageResource(R.drawable.ic_ui_cancel);
                actionButton.setContentDescription(itemView.getContext().getString(R.string.action_cancel_timer));
                actionButton.setAlpha(1f);
                actionButton.setEnabled(true);
                actionButton.setOnClickListener(v -> actionListener.onDismissTimer(timer));
                if (timer.isCompleted() && !timer.isNotificationDismissed()) {
                    startBlink();
                } else {
                    stopBlink();
                    statusView.setAlpha(1f);
                }
                } else if (timer.isPhaseAwaitingAnnouncement()) {
                statusView.setText(R.string.timer_status_announcing);
                statusView.setBackground(ContextCompat.getDrawable(itemView.getContext(),
                    R.drawable.bg_status_chip_running_yellow));
                statusView.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.accentWarm));
                remainingView.setText(itemView.getContext().getString(
                    R.string.timer_phase_announcement, timer.getAnnouncementName()));
                startBlink();
                actionButton.setImageResource(R.drawable.ic_ui_cancel);
                actionButton.setContentDescription(itemView.getContext().getString(R.string.action_cancel_timer));
                actionButton.setAlpha(1f);
                actionButton.setEnabled(true);
                actionButton.setOnClickListener(v -> actionListener.onCancelTimer(timer));
            } else {
                statusView.setText(R.string.timer_status_running);
                statusView.setBackground(ContextCompat.getDrawable(itemView.getContext(),
                        R.drawable.bg_status_chip_running_yellow));
                statusView.setTextColor(ContextCompat.getColor(itemView.getContext(),
                        R.color.accentWarm));
                startBlink();
                remainingView.setText(TimerFormatter.formatDuration(timer.getRemainingMillis(now)));
                actionButton.setImageResource(R.drawable.ic_ui_cancel);
                actionButton.setContentDescription(itemView.getContext().getString(R.string.action_cancel_timer));
                actionButton.setAlpha(1f);
                actionButton.setEnabled(true);
                actionButton.setOnClickListener(v -> actionListener.onCancelTimer(timer));
            }
        }

        private void bindTypeIcon(TimerType timerType) {
            int iconResource;
            int descriptionResource;
            if (timerType == TimerType.INTERVAL) {
                iconResource = R.drawable.ic_timer_interval;
                descriptionResource = R.string.timer_type_interval_choice;
            } else if (timerType == TimerType.GROUP) {
                iconResource = R.drawable.ic_timer_group;
                descriptionResource = R.string.timer_type_group_choice;
            } else {
                iconResource = R.drawable.ic_timer_standard;
                descriptionResource = R.string.timer_type_standard;
            }
            typeIconView.setImageResource(iconResource);
            typeIconView.setContentDescription(itemView.getContext().getString(descriptionResource));
        }

        private String getIntervalProgressLabel(ManagedTimer timer) {
            if (timer.getRepeatCount() == 0) {
                return itemView.getContext().getString(R.string.interval_progress_unlimited, timer.getCompletedCycles());
            }
            return itemView.getContext().getString(R.string.interval_progress_finite,
                    timer.getCompletedCycles(), timer.getRepeatCount());
        }

        private void startBlink() {
            if (blinkAnimator != null && blinkAnimator.isRunning()) return;
            blinkAnimator = ObjectAnimator.ofFloat(statusView, "alpha", 1f, 0.25f);
            blinkAnimator.setDuration(700);
            blinkAnimator.setRepeatMode(ObjectAnimator.REVERSE);
            blinkAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            blinkAnimator.setInterpolator(new LinearInterpolator());
            blinkAnimator.start();
        }

        private void stopBlink() {
            if (blinkAnimator != null) {
                blinkAnimator.cancel();
                blinkAnimator = null;
            }
        }
    }
}