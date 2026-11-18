/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Persistent, user-directed scheduling; preferences contain counts only. */
public final class KnowledgeBackgroundScheduler {
    public static final String PERIODIC_NAME = "knowledge_offline_periodic_v1";
    public static final String ONCE_NAME = "knowledge_offline_once_v1";
    public static final String DATA_PERIODIC = "periodic";
    public static final int LAST_NONE = 0;
    public static final int LAST_OK = 1;
    public static final int LAST_PARTIAL = 2;
    public static final int LAST_UNAVAILABLE = 3;
    public static final int LAST_ERROR = 4;

    private static final String PREFS = "knowledge_background_v1";
    private static final String ENABLED = "enabled";
    private static final String NOTIFICATIONS = "notify_changes";
    private static final String LAST_STATUS = "last_status";
    private static final String LAST_AT = "last_at";
    private static final String LAST_SCANNED = "last_scanned";
    private static final String LAST_CHANGED = "last_changed";

    private KnowledgeBackgroundScheduler() {
    }

    private static SharedPreferences prefs(final Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(final Context context) {
        return prefs(context).getBoolean(ENABLED, false);
    }

    public static boolean notificationsEnabled(final Context context) {
        return prefs(context).getBoolean(NOTIFICATIONS, false);
    }

    public static void setNotificationsEnabled(final Context context, final boolean enabled) {
        prefs(context).edit().putBoolean(NOTIFICATIONS, enabled).apply();
    }

    public static void setEnabled(final Context context, final boolean enabled) {
        final boolean previous = isEnabled(context);
        prefs(context).edit().putBoolean(ENABLED, enabled).commit();
        try {
            if (enabled) {
                final Constraints constraints = new Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build();
                final PeriodicWorkRequest request =
                        new PeriodicWorkRequest.Builder(KnowledgeBackgroundWorker.class,
                                6, TimeUnit.HOURS)
                                .setInputData(new Data.Builder().putBoolean(DATA_PERIODIC, true)
                                        .build())
                                .setConstraints(constraints)
                                .build();
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE, request);
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME);
            }
        } catch (RuntimeException schedulingFailed) {
            prefs(context).edit().putBoolean(ENABLED, previous).commit();
            throw schedulingFailed;
        }
    }

    public static void enqueueOnce(final Context context) {
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE_NAME,
                ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(KnowledgeBackgroundWorker.class).build());
    }

    public static void record(final Context context, final int status,
                              final int scanned, final int changed) {
        prefs(context).edit().putInt(LAST_STATUS, status)
                .putInt(LAST_SCANNED, scanned)
                .putInt(LAST_CHANGED, changed)
                .putLong(LAST_AT, System.currentTimeMillis()).apply();
    }

    public static int lastStatus(final Context context) {
        return prefs(context).getInt(LAST_STATUS, LAST_NONE);
    }

    public static int lastScanned(final Context context) {
        return prefs(context).getInt(LAST_SCANNED, 0);
    }

    public static int lastChanged(final Context context) {
        return prefs(context).getInt(LAST_CHANGED, 0);
    }

    public static long lastAt(final Context context) {
        return prefs(context).getLong(LAST_AT, 0L);
    }
}
