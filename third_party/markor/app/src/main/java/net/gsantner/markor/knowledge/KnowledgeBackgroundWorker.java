/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import net.gsantner.markor.R;
import net.gsantner.markor.model.AppSettings;

import java.io.File;
import java.io.IOException;

/**
 * Restartable, entirely local source-index projection. Never invokes Python
 * fact approval, embeddings, an LLM, a URL, or external storage for output.
 * A stopped SQLite transaction rolls back and the next WorkManager attempt
 * performs a fresh source traversal; the notes and knowledge journal are safe.
 */
public final class KnowledgeBackgroundWorker extends Worker {
    private static final Object INDEX_JOB_GATE = new Object();
    private static final String RUN_CHANNEL = "knowledge_local_scan_v1";
    private static final String CHANGE_CHANNEL = "knowledge_change_counts_v1";
    private static final int RUN_NOTIFICATION = 40481;
    private static final int CHANGE_NOTIFICATION = 40482;

    public KnowledgeBackgroundWorker(@NonNull final Context context,
                                     @NonNull final WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        final Context context = getApplicationContext();
        final boolean periodic = getInputData().getBoolean(
                KnowledgeBackgroundScheduler.DATA_PERIODIC, false);
        if (periodic && !KnowledgeBackgroundScheduler.isEnabled(context)) {
            return Result.success();
        }
        final File notebook = AppSettings.get(context).getNotebookDirectory();
        if (notebook == null || !notebook.isDirectory()) {
            KnowledgeBackgroundScheduler.record(context,
                    KnowledgeBackgroundScheduler.LAST_UNAVAILABLE, 0, 0);
            return Result.success();
        }

        // User-visible foreground work can outlive the normal 10-minute window.
        // Notification contains only counts, never note titles, paths or text.
        try {
            setForegroundAsync(foregroundInfo(0, false)).get();
        } catch (Exception foregroundDenied) {
            KnowledgeBackgroundScheduler.record(context,
                    KnowledgeBackgroundScheduler.LAST_ERROR, 0, 0);
            return Result.retry();
        }

        synchronized (INDEX_JOB_GATE) {
            try {
                final KnowledgeLocalIndex index = new KnowledgeLocalIndex(context);
                final boolean alreadyIndexed = index.hasCommittedScanFor(notebook);
                final KnowledgeVaultScanner.Cancellation cancelled = () -> isStopped()
                        || Thread.currentThread().isInterrupted()
                        || (periodic && !KnowledgeBackgroundScheduler.isEnabled(context));
                final KnowledgeVaultScanner.Snapshot snapshot = KnowledgeVaultScanner.scan(
                        notebook, KnowledgeVaultScanner.MAX_NOTES, cancelled,
                        (notes, directories) -> publishProgress(notes, false));
                final KnowledgeLocalIndex.SyncResult sync = index.synchronize(
                        notebook, snapshot, cancelled,
                        (processed, total) -> publishProgress(processed, true));
                final int changed = KnowledgeBackgroundPolicy.changedNotes(
                        sync.indexed, sync.removed);
                KnowledgeBackgroundScheduler.record(context,
                        sync.keptMissingEntries ? KnowledgeBackgroundScheduler.LAST_PARTIAL
                                : KnowledgeBackgroundScheduler.LAST_OK,
                        snapshot.notes.size(), changed);
                if (!isStopped() && KnowledgeBackgroundPolicy.shouldNotify(
                        KnowledgeBackgroundScheduler.notificationsEnabled(context),
                        alreadyIndexed, !sync.keptMissingEntries, sync.indexed, sync.removed)) {
                    showChangedNotification(changed);
                }
                return Result.success();
            } catch (IOException interruptedOrUnavailable) {
                if (!isStopped()) {
                    KnowledgeBackgroundScheduler.record(context,
                            KnowledgeBackgroundScheduler.LAST_ERROR, 0, 0);
                }
                return Result.retry();
            } catch (RuntimeException invalidIndexOrPermission) {
                KnowledgeBackgroundScheduler.record(context,
                        KnowledgeBackgroundScheduler.LAST_ERROR, 0, 0);
                // A corrupt local projection requires the explicit recovery UI;
                // do not silently erase any data or spin an immediate retry.
                return Result.failure();
            }
        }
    }

    private void publishProgress(final int count, final boolean indexing) {
        if (!isStopped()) {
            setProgressAsync(new Data.Builder().putInt("notes", count)
                    .putBoolean("indexing", indexing).build());
            setForegroundAsync(foregroundInfo(count, indexing));
        }
    }

    private ForegroundInfo foregroundInfo(final int notes, final boolean indexing) {
        final Context context = getApplicationContext();
        final NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel(manager, RUN_CHANNEL, R.string.knowledge_background_channel,
                NotificationManager.IMPORTANCE_LOW);
        final Notification notification = new NotificationCompat.Builder(context, RUN_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(context.getString(R.string.knowledge_background_running))
                .setContentText(context.getString(indexing
                        ? R.string.knowledge_background_indexing
                        : R.string.knowledge_background_scanning, notes))
                .setContentIntent(openLibraryIntent())
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        return new ForegroundInfo(RUN_NOTIFICATION, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    private void showChangedNotification(final int changed) {
        final Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        final NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel(manager, CHANGE_CHANNEL, R.string.knowledge_background_change_channel,
                NotificationManager.IMPORTANCE_DEFAULT);
        final Notification notification = new NotificationCompat.Builder(context, CHANGE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(context.getString(R.string.knowledge_background_changes_title))
                .setContentText(context.getString(R.string.knowledge_background_changes, changed))
                .setContentIntent(openLibraryIntent())
                .setAutoCancel(true)
                .build();
        manager.notify(CHANGE_NOTIFICATION, notification);
    }

    private PendingIntent openLibraryIntent() {
        final Context context = getApplicationContext();
        final Intent intent = new Intent(context, KnowledgeHubActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 4048, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void ensureChannel(final NotificationManager manager, final String id,
                               final int title, final int importance) {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(id,
                    getApplicationContext().getString(title), importance));
        }
    }
}
