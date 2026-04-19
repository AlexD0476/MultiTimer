package com.example.multitimer;

import android.animation.ObjectAnimator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class TimerAdapter extends RecyclerView.Adapter<TimerAdapter.TimerViewHolder> {
    interface OnTimerActionListener {
        void onRestartTimer(ManagedTimer timer);

        void onCancelTimer(ManagedTimer timer);

        void onDeleteTimer(ManagedTimer timer);

        void onDismissNotification(ManagedTimer timer);

        void onEditTimer(ManagedTimer timer);
    }

    private final List<ManagedTimer> timers = new ArrayList<>();
    private final OnTimerActionListener actionListener;

    TimerAdapter(OnTimerActionListener actionListener) {
        this.actionListener = actionListener;
    }

    void submitList(List<ManagedTimer> nextTimers) {
        timers.clear();
        timers.addAll(nextTimers);
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
        holder.bind(timers.get(position), actionListener);
    }

    @Override
    public int getItemCount() {
        return timers.size();
    }

    static final class TimerViewHolder extends RecyclerView.ViewHolder {
        private final TextView titleView;
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
            statusView = itemView.findViewById(R.id.timerStatus);
            remainingView = itemView.findViewById(R.id.timerRemaining);
            durationView = itemView.findViewById(R.id.timerDuration);
            actionButton = itemView.findViewById(R.id.timerActionButton);
            notificationButton = itemView.findViewById(R.id.timerNotificationButton);
            deleteButton = itemView.findViewById(R.id.timerDeleteButton);
        }

        void bind(ManagedTimer timer, OnTimerActionListener actionListener) {
            long now = System.currentTimeMillis();
            titleView.setText(timer.getName());
            titleView.setOnClickListener(v -> actionListener.onEditTimer(timer));
            durationView.setText(itemView.getContext().getString(
                    R.string.timer_duration_template,
                    TimerFormatter.formatDuration(timer.getDurationMillis())
            ));

            if (timer.isCompleted() || timer.isCancelled()) {
                statusView.setText(timer.isCompleted()
                        ? R.string.timer_status_completed
                        : R.string.timer_status_cancelled);
                statusView.setBackground(ContextCompat.getDrawable(itemView.getContext(),
                        timer.isCompleted()
                                ? R.drawable.bg_status_chip_completed
                                : R.drawable.bg_status_chip_cancelled));
                statusView.setTextColor(ContextCompat.getColor(itemView.getContext(),
                        timer.isCompleted() ? R.color.accentPrimaryDark : R.color.statusRedStroke));
                remainingView.setText("");
                // Play-Button anzeigen
                actionButton.setImageResource(android.R.drawable.ic_media_play);
                actionButton.setContentDescription(itemView.getContext().getString(R.string.action_restart_timer));
                actionButton.setAlpha(1f);
                actionButton.setEnabled(true);
                actionButton.setOnClickListener(v -> actionListener.onRestartTimer(timer));
                // Stummschalten: nur bei fertigen Timern aktiv (nicht bei abgebrochenen)
                boolean notifActive = timer.isCompleted() && !timer.isNotificationDismissed();
                notificationButton.setAlpha(notifActive ? 1f : 0.3f);
                notificationButton.setEnabled(notifActive);
                notificationButton.setOnClickListener(notifActive ? v -> actionListener.onDismissNotification(timer) : null);
                if (notifActive) {
                    startBlink();
                } else {
                    stopBlink();
                    statusView.setAlpha(1f);
                }
                // Löschen: aktiv
                deleteButton.setAlpha(1f);
                deleteButton.setEnabled(true);
                deleteButton.setOnClickListener(v -> actionListener.onDeleteTimer(timer));
            } else {
                statusView.setText(R.string.timer_status_running);
                statusView.setBackground(ContextCompat.getDrawable(itemView.getContext(),
                        R.drawable.bg_status_chip_running_yellow));
                statusView.setTextColor(ContextCompat.getColor(itemView.getContext(),
                        R.color.accentWarm));
                startBlink();
                remainingView.setText(TimerFormatter.formatDuration(timer.getRemainingMillis(now)));
                // Abbrechen-Button anzeigen
                actionButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
                actionButton.setContentDescription(itemView.getContext().getString(R.string.action_cancel_timer));
                actionButton.setAlpha(1f);
                actionButton.setEnabled(true);
                actionButton.setOnClickListener(v -> actionListener.onCancelTimer(timer));
                // Stummschalten: inaktiv
                notificationButton.setAlpha(0.3f);
                notificationButton.setEnabled(false);
                notificationButton.setOnClickListener(null);
                // Löschen: inaktiv
                deleteButton.setAlpha(0.3f);
                deleteButton.setEnabled(false);
                deleteButton.setOnClickListener(null);
            }
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