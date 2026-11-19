/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Local daily backup contract. No network, vault paths or secrets in status. */
final class KnowledgeDailyBackup {
    static final String PERIODIC = "dpks_stage21_local_backup_daily";
    static final String ONCE = "dpks_stage21_local_backup_now";
    static final String PREF = "dpks_stage21_daily_backup_v1";
    static final String ENABLED = "enabled";
    static final String LAST_AT = "last_at";
    static final String LAST_COUNT = "last_count";
    static final String LAST_BYTES = "last_bytes";
    static final String LAST_OK = "last_ok";
    static final String VERIFIED = "verified_names";

    private KnowledgeDailyBackup() { }

    static SharedPreferences prefs(final Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static boolean enabled(final Context context) {
        return prefs(context).getBoolean(ENABLED, false);
    }

    static void start(final Context context, final char[] passphrase) throws Exception {
        // Do not schedule a persistent job until the user can recover the
        // passphrase on a new device and it is sealed to Android Keystore.
        KnowledgeBackupKeyStore.save(context, passphrase);
        final Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true).build();
        final PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                KnowledgeDailyBackupWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request);
        prefs(context).edit().putBoolean(ENABLED, true).commit();
    }

    static void stop(final Context context) {
        prefs(context).edit().putBoolean(ENABLED, false).commit();
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC);
        WorkManager.getInstance(context).cancelUniqueWork(ONCE);
        KnowledgeBackupKeyStore.clear(context);
    }

    static void now(final Context context) {
        if (!enabled(context)) throw new IllegalStateException("Backup not configured");
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE,
                ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(KnowledgeDailyBackupWorker.class).build());
    }
}
