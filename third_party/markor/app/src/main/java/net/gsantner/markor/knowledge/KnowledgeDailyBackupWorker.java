/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.documentfile.provider.DocumentFile;

import net.gsantner.markor.R;
import net.gsantner.markor.model.AppSettings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * User-enabled daily copy into app-specific local external storage. The
 * Keystore never leaves this device; the separately memorized passphrase
 * recovers the archives on a clean installation. SAF/cloud copy is separate.
 */
public final class KnowledgeDailyBackupWorker extends Worker {
    private static final String CHANNEL = "dpks_daily_backup_stage21";
    private static final int NOTIFICATION = 40521;
    private static final Object GATE = new Object();

    public KnowledgeDailyBackupWorker(@NonNull final Context context,
                                      @NonNull final WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        final Context context = getApplicationContext();
        if (!KnowledgeDailyBackup.enabled(context)) return Result.success();
        try {
            setForegroundAsync(foreground()).get();
        } catch (Exception denied) {
            return Result.retry();
        }
        synchronized (GATE) {
            if (isStopped() || !KnowledgeDailyBackup.enabled(context))
                return Result.success();
            final File notebook = AppSettings.get(context).getNotebookDirectory();
            final File external = context.getExternalFilesDir(null);
            if (notebook == null || !notebook.isDirectory() || external == null)
                return failed(context);
            final File directory = new File(external, "DPKS_daily_backups");
            if (!directory.isDirectory() && !directory.mkdirs()) return failed(context);
            final File journal = new File(new File(context.getNoBackupFilesDir(),
                    "knowledge_workspace_v1"), "data/formal/journal.jsonl");
            char[] passphrase = null;
            File tmp = null;
            File stage = null;
            try {
                passphrase = KnowledgeBackupKeyStore.read(context);
                if (journal.isFile()) KnowledgePythonBridge.journalExportInfo(
                        context, new File(context.getNoBackupFilesDir(),
                                "knowledge_workspace_v1").getAbsolutePath());
                final String filename = "DPKS_daily_" + System.currentTimeMillis() + "_"
                        + Long.toHexString(new SecureRandom().nextLong()) + ".dpksb";
                tmp = new File(directory, filename + ".partial");
                final File permanent = new File(directory, filename);
                if (tmp.exists() || permanent.exists()) throw new IllegalStateException("Collision");
                final KnowledgePortableArchive.Cancellation cancelled = () -> isStopped()
                        || !KnowledgeDailyBackup.enabled(context);
                final KnowledgePortableArchive.Summary created;
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    final JSONObject settings = new JSONObject();
                    settings.put("schema", "dpks-safe-settings-v1");
                    settings.put("secrets_exported", false);
                    settings.put("derived_indexes_exported", false);
                    created = KnowledgePortableArchive.write(notebook,
                            journal.isFile() ? journal : null,
                            settings.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            KnowledgePortableCipher.encrypt(out, passphrase), cancelled);
                }
                try (FileInputStream ciphertext = new FileInputStream(tmp)) {
                    stage = KnowledgePortableCipher.decryptToVerifiedStaging(ciphertext,
                            context.getCacheDir(), passphrase, cancelled);
                }
                final KnowledgePortableArchive.Summary checked =
                        KnowledgePortableArchive.verify(stage, cancelled);
                if (created.sources != checked.sources || created.bytes != checked.bytes
                        || created.journal != checked.journal || !checked.settings)
                    throw new IllegalStateException("Backup verification mismatch");
                if (isStopped() || !KnowledgeDailyBackup.enabled(context))
                    throw new IllegalStateException("Backup cancelled");
                if (!tmp.renameTo(permanent))
                    throw new IllegalStateException("Cannot finalize verified backup");
                tmp = null;
                // Never remove an old backup before a replacement has been
                // fully written, decrypted, authenticated and hashed.
                recordVerified(context, directory, filename, checked);
                // The user may separately consent to a SAF destination. This
                // can be a local disk or an Android cloud document provider:
                // only the already-encrypted .dpksb is ever written there.
                final Uri externalTree = KnowledgeExternalBackup.destination(context);
                if (externalTree != null) {
                    try {
                        copyVerifiedToSaf(context, externalTree, permanent,
                                filename, passphrase, checked, cancelled);
                        KnowledgeExternalBackup.record(context, true);
                    } catch (Exception unavailable) {
                        // Independent states: the local verified backup is
                        // successful even when cloud/SAF copy is unavailable.
                        KnowledgeExternalBackup.record(context, false);
                    }
                }
                return Result.success();
            } catch (Exception failed) {
                return failed(context);
            } finally {
                if (passphrase != null) Arrays.fill(passphrase, '\0');
                if (stage != null) stage.delete();
                if (tmp != null) tmp.delete();
            }
        }
    }

    private static void copyVerifiedToSaf(final Context context, final Uri folder,
                                          final File encrypted, final String filename,
                                          final char[] passphrase,
                                          final KnowledgePortableArchive.Summary expected,
                                          final KnowledgePortableArchive.Cancellation cancelled)
            throws Exception {
        final DocumentFile tree = DocumentFile.fromTreeUri(context, folder);
        if (tree == null || !tree.isDirectory() || !tree.canWrite())
            throw new IllegalStateException("SAF destination not writable");
        final DocumentFile output = tree.createFile("application/octet-stream", filename);
        if (output == null) throw new IllegalStateException("Cannot create SAF backup");
        boolean confirmed = false;
        File stage = null;
        try {
            try (InputStream from = new FileInputStream(encrypted);
                 OutputStream to = context.getContentResolver().openOutputStream(
                         output.getUri(), "w")) {
                if (to == null) throw new IllegalStateException("SAF output unavailable");
                final byte[] buffer = new byte[65536];
                int n;
                while ((n = from.read(buffer)) >= 0) {
                    if (cancelled.cancelled()) throw new IllegalStateException("Copy cancelled");
                    to.write(buffer, 0, n);
                }
                to.flush();
            }
            // SAF providers can claim success before durable upload. Re-open
            // and authenticate the actual bytes returned by that provider.
            try (InputStream from = context.getContentResolver().openInputStream(
                    output.getUri())) {
                if (from == null) throw new IllegalStateException("SAF readback unavailable");
                stage = KnowledgePortableCipher.decryptToVerifiedStaging(
                        from, context.getCacheDir(), passphrase, cancelled);
            }
            final KnowledgePortableArchive.Summary checked =
                    KnowledgePortableArchive.verify(stage, cancelled);
            if (checked.sources != expected.sources || checked.bytes != expected.bytes
                    || checked.journal != expected.journal || !checked.settings)
                throw new IllegalStateException("SAF backup verification mismatch");
            confirmed = true;
        } finally {
            if (stage != null) stage.delete();
            // Only this newly created unverified document can be removed.
            // Previous verified SAF backups are never deleted automatically.
            if (!confirmed) output.delete();
        }
    }

    private static void recordVerified(final Context context, final File directory,
                                       final String filename,
                                       final KnowledgePortableArchive.Summary info) {
        final android.content.SharedPreferences prefs = KnowledgeDailyBackup.prefs(context);
        final List<String> names = new ArrayList<>();
        for (final String name : prefs.getString(KnowledgeDailyBackup.VERIFIED, "").split(",")) {
            if (name.matches("DPKS_daily_[0-9]+_[0-9a-f]+\\.dpksb")
                    && new File(directory, name).isFile()) names.add(name);
        }
        names.add(filename);
        Collections.sort(names);
        // Keep three recent known-good backups, with no deletion if fewer
        // than three have ever been independently verified.
        while (names.size() > 3) {
            final String old = names.get(0);
            final File candidate = new File(directory, old);
            if (!candidate.isFile() || !candidate.delete()) break;
            names.remove(0);
        }
        prefs.edit().putBoolean(KnowledgeDailyBackup.LAST_OK, true)
                .putLong(KnowledgeDailyBackup.LAST_AT, System.currentTimeMillis())
                .putLong(KnowledgeDailyBackup.LAST_BYTES, info.bytes)
                .putInt(KnowledgeDailyBackup.LAST_COUNT, info.sources)
                .putString(KnowledgeDailyBackup.VERIFIED, android.text.TextUtils.join(",", names))
                .commit();
    }

    private static Result failed(final Context context) {
        KnowledgeDailyBackup.prefs(context).edit()
                .putBoolean(KnowledgeDailyBackup.LAST_OK, false).commit();
        return Result.retry();
    }

    private ForegroundInfo foreground() {
        final Context context = getApplicationContext();
        final NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    context.getString(R.string.knowledge_daily_backup_title),
                    NotificationManager.IMPORTANCE_LOW));
        }
        final Notification notification = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(context.getString(R.string.knowledge_daily_backup_title))
                .setContentText(context.getString(R.string.knowledge_daily_backup_running))
                .setOnlyAlertOnce(true).setSilent(true).setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW).build();
        return new ForegroundInfo(NOTIFICATION, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }
}
