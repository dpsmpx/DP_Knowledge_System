/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import net.gsantner.markor.BuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Manual, verify-only E5 transport coordinator. No model execution or UI hook.
 * start() is a MAIN-thread operation; the full package audit and stream copy
 * run in the sender thread. The receiver's SHA match alone never means success:
 * the sender must also complete its final source-FD audit and close the pipe.
 */
final class KnowledgeE5VerifyOnlyFdClient {
    interface Callback {
        /** Only says both byte checks agreed; not an ONNX/model trust grant. */
        void match();
        /** Optional completion diagnostics; older callers still receive match(). */
        default void matchWithDiagnostics(final KnowledgeE5FdVerificationMetrics.Report report) {
            match();
        }
        void failure();
    }

    private interface Source {
        void send(OutputStream untrustedPipe) throws IOException;
    }

    static final long CLIENT_TIMEOUT_MILLIS =
            KnowledgeOnnxIsolatedProtocol.VERIFY_ONLY_WATCHDOG_MILLIS + 5_000L;

    private final Context context;
    private final Handler main;
    private final Messenger inbox;
    private long timeoutMillis;
    private ServiceConnection connection;
    private Messenger remote;
    private IBinder binder;
    private IBinder.DeathRecipient binderDeath;
    private Callback callback;
    private ParcelFileDescriptor writePipe;
    private Thread senderThread;
    private boolean bound;
    private boolean senderComplete;
    private boolean receiverMatched;
    private int sequence;
    private int activeRequest;
    private long activeBytes;
    private long startedNanos;
    private final Runnable deadline = this::notifyFailure;

    KnowledgeE5VerifyOnlyFdClient(final Context owner) {
        this(owner, 0L);
    }

    /** Shortened timeout is available only to debug instrumentation. */
    KnowledgeE5VerifyOnlyFdClient(final Context owner, final long debugTimeoutMillis) {
        context = owner;
        timeoutMillis = BuildConfig.DEBUG && debugTimeoutMillis >= 250L
                && debugTimeoutMillis < CLIENT_TIMEOUT_MILLIS
                ? debugTimeoutMillis : CLIENT_TIMEOUT_MILLIS;
        main = new Handler(Looper.getMainLooper());
        inbox = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    }

    void restoreDefaultTimeoutForDebugTest() {
        if (BuildConfig.DEBUG) timeoutMillis = CLIENT_TIMEOUT_MILLIS;
    }

    /**
     * Must be called only following an explicit user choice; start() itself
     * never runs on a background timer or touches any file on the UI thread.
     * The package store audits *all* installed files before the first pipe byte.
     */
    boolean start(final KnowledgeModelPackageStore packages,
                  final KnowledgeModelFiles.Entry entry, final Callback result) {
        if (packages == null || entry == null
                || entry.format != KnowledgeModelFiles.Format.ONNX_UNVERIFIED) return false;
        return startRequest(entry.bytes, entry.sha256,
                sink -> packages.copyAuditedE5WeightToQuarantine(entry, sink), result);
    }

    /** CI-only path to exercise the SAME Binder lifecycle without any real file. */
    boolean startDebugSyntheticFixture(final Callback result) {
        if (!BuildConfig.DEBUG) return false;
        return startRequest(KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                KnowledgeE5LargeFdFixture.SHA256, sink -> {
                    try (InputStream synthetic = KnowledgeE5LargeFdFixture.open()) {
                        KnowledgeE5LargeFdTransfer.copyToQuarantine(synthetic, sink,
                                KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                                KnowledgeE5LargeFdFixture.SHA256);
                    }
                }, result);
    }

    /** CI-only postflight rejection even though the receiver gets valid bytes. */
    boolean startDebugPostflightFailure(final Callback result) {
        if (!BuildConfig.DEBUG) return false;
        return startRequest(KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                KnowledgeE5LargeFdFixture.SHA256, sink -> {
                    try (InputStream synthetic = KnowledgeE5LargeFdFixture.open()) {
                        KnowledgeE5LargeFdTransfer.copyToQuarantine(synthetic, sink,
                                KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                                KnowledgeE5LargeFdFixture.SHA256);
                    }
                    throw new IOException("Synthetic sender postflight rejection");
                }, result);
    }

    private boolean startRequest(final long length, final String sha256,
                                 final Source source, final Callback result) {
        if (Looper.myLooper() != Looper.getMainLooper() || result == null
                || connection != null || callback != null
                || !KnowledgeOnnxIsolatedProtocol.validVerifyOnlyFdEnvelope(length, sha256)) {
            return false;
        }
        callback = result;
        senderComplete = false;
        receiverMatched = false;
        activeRequest = ++sequence;
        activeBytes = length;
        startedNanos = System.nanoTime();
        final int requestId = activeRequest;
        final ServiceConnection current = new ServiceConnection() {
            @Override public void onServiceConnected(final ComponentName name,
                                                     final IBinder service) {
                if (connection != this) return;
                if (service == null) {
                    notifyFailure();
                    return;
                }
                final ServiceConnection owningConnection = this;
                final IBinder.DeathRecipient died = () -> main.post(() -> {
                    if (connection == owningConnection) notifyFailure();
                });
                try {
                    service.linkToDeath(died, 0);
                    binder = service;
                    binderDeath = died;
                    remote = new Messenger(service);
                } catch (RemoteException | RuntimeException unavailable) {
                    notifyFailure();
                    return;
                }
                ParcelFileDescriptor readPipe = null;
                try {
                    final ParcelFileDescriptor[] pipes = ParcelFileDescriptor.createPipe();
                    readPipe = pipes[0];
                    writePipe = pipes[1];
                    final Message request = Message.obtain(null,
                            KnowledgeOnnxIsolatedProtocol.VERIFY_ONLY_LARGE_FD);
                    request.arg1 = requestId;
                    request.replyTo = inbox;
                    final Bundle data = new Bundle();
                    data.putParcelable(KnowledgeOnnxIsolatedProtocol.PIPE_FD, readPipe);
                    data.putLong(KnowledgeOnnxIsolatedProtocol.VERIFY_ONLY_FD_LENGTH, length);
                    data.putString(KnowledgeOnnxIsolatedProtocol.MODEL_SHA256, sha256);
                    request.setData(data);
                    remote.send(request);
                    final ParcelFileDescriptor ownedOutput = writePipe;
                    final Thread sender = new Thread(
                            () -> sendBytes(source, ownedOutput, requestId),
                            "dpks-e5-verify-only-sender");
                    sender.setDaemon(true);
                    senderThread = sender;
                    sender.start();
                } catch (IOException | RemoteException | RuntimeException unavailable) {
                    notifyFailure();
                } finally {
                    closePipe(readPipe);
                }
            }

            @Override public void onServiceDisconnected(final ComponentName name) {
                if (connection == this) notifyFailure();
            }

            @Override public void onBindingDied(final ComponentName name) {
                if (connection == this) notifyFailure();
            }

            @Override public void onNullBinding(final ComponentName name) {
                if (connection == this) notifyFailure();
            }
        };
        connection = current;
        try {
            bound = context.bindService(new Intent(context, KnowledgeOnnxIsolatedService.class),
                    current, Context.BIND_AUTO_CREATE);
            if (!bound) {
                finish();
                return false;
            }
            main.postDelayed(deadline, timeoutMillis);
            return true;
        } catch (RuntimeException unavailable) {
            finish();
            return false;
        }
    }

    private void sendBytes(final Source source, final ParcelFileDescriptor output,
                           final int requestId) {
        try (OutputStream pipe = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
            source.send(pipe);
        } catch (IOException | RuntimeException rejected) {
            // No file paths, model bytes or internal diagnostics cross Binder.
            main.post(() -> senderFinished(requestId, false));
            return;
        }
        main.post(() -> senderFinished(requestId, true));
    }

    private void senderFinished(final int requestId, final boolean successful) {
        if (callback == null || activeRequest != requestId) return;
        senderThread = null;
        writePipe = null; // AutoCloseOutputStream owns and has closed it.
        if (!successful) {
            notifyFailure();
        } else {
            senderComplete = true;
            completeIfReady();
        }
    }

    private boolean receive(final Message response) {
        if (callback == null || connection == null || response.arg1 != activeRequest) {
            return true;
        }
        if (response.what == KnowledgeOnnxIsolatedProtocol.RESULT_VERIFY_ONLY_MISMATCH) {
            notifyFailure();
        } else if (response.what == KnowledgeOnnxIsolatedProtocol.RESULT_VERIFY_ONLY_MATCH) {
            receiverMatched = true;
            completeIfReady();
        }
        return true;
    }

    private void completeIfReady() {
        if (!senderComplete || !receiverMatched || callback == null) return;
        final KnowledgeE5FdVerificationMetrics.Report diagnostics =
                KnowledgeE5FdVerificationMetrics.fromElapsedMillis(activeBytes,
                        Math.max(1L, (System.nanoTime() - startedNanos) / 1_000_000L));
        final Callback done = finish();
        if (done != null) done.matchWithDiagnostics(diagnostics);
    }

    private void notifyFailure() {
        final Callback done = callback;
        cancel();
        if (done != null) done.failure();
    }

    /** Caller cancellation deliberately does not report success or failure. */
    void cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::cancel);
            return;
        }
        if (remote != null) {
            try {
                remote.send(Message.obtain(null,
                        KnowledgeOnnxIsolatedProtocol.CANCEL_AND_EXIT));
            } catch (RemoteException ignored) { }
        }
        finish();
    }

    private Callback finish() {
        main.removeCallbacks(deadline);
        final Callback done = callback;
        callback = null;
        remote = null;
        if (binder != null && binderDeath != null) {
            binder.unlinkToDeath(binderDeath, 0);
        }
        binder = null;
        binderDeath = null;
        final ServiceConnection old = connection;
        connection = null;
        closePipe(writePipe);
        writePipe = null;
        if (senderThread != null) {
            senderThread.interrupt();
            senderThread = null;
        }
        senderComplete = false;
        receiverMatched = false;
        activeBytes = 0L;
        startedNanos = 0L;
        if (old != null && bound) {
            try {
                context.unbindService(old);
            } catch (IllegalArgumentException ignored) { }
        }
        bound = false;
        return done;
    }

    private static void closePipe(final ParcelFileDescriptor pipe) {
        if (pipe == null) return;
        try {
            pipe.close();
        } catch (IOException ignored) { }
    }
}
