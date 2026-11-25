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
import java.io.OutputStream;
import java.util.Arrays;

/** A single manually approved, already-audited model transfer over an FD pipe. */
final class KnowledgeOnnxIsolatedSessionClient {
    interface Callback {
        void success(KnowledgeOnnxSessionGate.Report report);
        void failure();
    }

    private final Context context;
    private final Handler mainHandler;
    private final Messenger inbox;
    private ServiceConnection connection;
    private Messenger remote;
    private IBinder activeBinder;
    private IBinder.DeathRecipient binderDeath;
    private Callback callback;
    private byte[] pendingBytes;
    private ParcelFileDescriptor writePipe;
    private Thread senderThread;
    private boolean bound;
    private int sequence;
    private int activeRequestId;
    private long timeoutMillis;
    private final Runnable deadline = this::notifyFailure;

    KnowledgeOnnxIsolatedSessionClient(final Context owner) {
        this(owner, 0L);
    }

    /** The production client always retains its full 35-second deadline. */
    KnowledgeOnnxIsolatedSessionClient(final Context owner, final long debugTimeoutMillis) {
        context = owner;
        timeoutMillis = BuildConfig.DEBUG && debugTimeoutMillis >= 250L
                && debugTimeoutMillis < KnowledgeOnnxIsolatedProtocol.CLIENT_TIMEOUT_MILLIS
                ? debugTimeoutMillis : KnowledgeOnnxIsolatedProtocol.CLIENT_TIMEOUT_MILLIS;
        mainHandler = new Handler(Looper.getMainLooper());
        inbox = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    }

    void restoreDefaultTimeoutForDebugTest() {
        if (BuildConfig.DEBUG) timeoutMillis = KnowledgeOnnxIsolatedProtocol.CLIENT_TIMEOUT_MILLIS;
    }

    /** Takes ownership of bytes even when binding fails; never retains a file path. */
    boolean start(final byte[] auditedModel, final String expectedSha256, final Callback result) {
        if (auditedModel == null) return false;
        if (result == null || connection != null || auditedModel.length < 16
                || auditedModel.length > KnowledgeOnnxSessionGate.MAX_MODEL_BYTES
                || !KnowledgeOnnxSessionPipe.validSha256(expectedSha256)) {
            Arrays.fill(auditedModel, (byte) 0);
            return false;
        }
        callback = result;
        pendingBytes = auditedModel;
        activeRequestId = ++sequence;
        final int requestId = activeRequestId;
        final ServiceConnection next = new ServiceConnection() {
            @Override public void onServiceConnected(final ComponentName name,
                                                     final IBinder service) {
                if (connection != this) return;
                if (service == null) {
                    notifyFailure();
                    return;
                }
                final ServiceConnection requestConnection = this;
                final IBinder.DeathRecipient died = () -> mainHandler.post(() -> {
                    if (connection == requestConnection) notifyFailure();
                });
                try {
                    service.linkToDeath(died, 0);
                    activeBinder = service;
                    binderDeath = died;
                    remote = new Messenger(service);
                } catch (RemoteException | RuntimeException dead) {
                    notifyFailure();
                    return;
                }
                ParcelFileDescriptor readPipe = null;
                try {
                    final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                    readPipe = pipe[0];
                    writePipe = pipe[1];
                    final Message request = Message.obtain(null,
                            KnowledgeOnnxIsolatedProtocol.RUN_MODEL_SESSION);
                    request.arg1 = requestId;
                    request.arg2 = pendingBytes.length;
                    request.replyTo = inbox;
                    final Bundle data = new Bundle();
                    data.putParcelable(KnowledgeOnnxIsolatedProtocol.PIPE_FD, readPipe);
                    data.putString(KnowledgeOnnxIsolatedProtocol.MODEL_SHA256, expectedSha256);
                    request.setData(data);
                    remote.send(request);
                    final byte[] owned = pendingBytes;
                    final ParcelFileDescriptor output = writePipe;
                    final Thread sender = new Thread(() -> streamBytes(owned, output, requestId),
                            "dpks-onnx-session-pipe");
                    sender.setDaemon(true);
                    senderThread = sender;
                    sender.start();
                } catch (IOException | RemoteException | RuntimeException failed) {
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
        connection = next;
        try {
            bound = context.bindService(new Intent(context, KnowledgeOnnxIsolatedService.class),
                    next, Context.BIND_AUTO_CREATE);
            if (!bound) {
                finish();
                return false;
            }
            mainHandler.postDelayed(deadline, timeoutMillis);
            return true;
        } catch (RuntimeException unavailable) {
            finish();
            return false;
        }
    }

    private void streamBytes(final byte[] bytes, final ParcelFileDescriptor output,
                             final int requestId) {
        try (OutputStream sink = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
            for (int offset = 0; offset < bytes.length;) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Transfer cancelled");
                }
                final int count = Math.min(64 * 1024, bytes.length - offset);
                sink.write(bytes, offset, count);
                offset += count;
            }
        } catch (IOException failed) {
            mainHandler.post(() -> {
                if (activeRequestId == requestId && callback != null) notifyFailure();
            });
        } finally {
            Arrays.fill(bytes, (byte) 0);
            mainHandler.post(() -> {
                if (activeRequestId == requestId) {
                    pendingBytes = null;
                    writePipe = null;
                    senderThread = null;
                }
            });
        }
    }

    private boolean receive(final Message response) {
        if (callback == null || connection == null || response.arg1 != activeRequestId) {
            return true;
        }
        if (response.what != KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_PASSED
                && response.what != KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_FAILED
                && response.what != KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_MEMORY_DENIED) {
            return true;
        }
        KnowledgeOnnxSessionGate.Report report = null;
        if (response.what == KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_PASSED) {
            final Bundle data = response.getData();
            final long millis = data.getLong(KnowledgeOnnxIsolatedProtocol.INIT_MILLIS, -1);
            final long inputs = data.getLong(KnowledgeOnnxIsolatedProtocol.INPUTS, -1);
            final long outputs = data.getLong(KnowledgeOnnxIsolatedProtocol.OUTPUTS, -1);
            final long before = data.getLong(KnowledgeOnnxIsolatedProtocol.PSS_BEFORE_KIB, -1);
            final long observed = data.getLong(KnowledgeOnnxIsolatedProtocol.PSS_OBSERVED_KIB, -1);
            final long after = data.getLong(KnowledgeOnnxIsolatedProtocol.PSS_AFTER_KIB, -1);
            final long rssHighWater = data.getLong(
                    KnowledgeOnnxIsolatedProtocol.RSS_HIGH_WATER_KIB, -1L);
            final KnowledgeOnnxIoSummary io = new KnowledgeOnnxIoSummary(
                    data.getInt(KnowledgeOnnxIsolatedProtocol.INPUT_TENSORS, -1),
                    data.getInt(KnowledgeOnnxIsolatedProtocol.OUTPUT_TENSORS, -1),
                    data.getInt(KnowledgeOnnxIsolatedProtocol.FLOAT_INPUTS, -1),
                    data.getInt(KnowledgeOnnxIsolatedProtocol.FLOAT_OUTPUTS, -1),
                    data.getInt(KnowledgeOnnxIsolatedProtocol.INT64_INPUTS, -1),
                    data.getInt(KnowledgeOnnxIsolatedProtocol.INT64_OUTPUTS, -1),
                    data.getInt(KnowledgeOnnxIsolatedProtocol.DYNAMIC_INPUT_AXES, -1),
                    data.getInt(KnowledgeOnnxIsolatedProtocol.DYNAMIC_OUTPUT_AXES, -1),
                    data.getInt(KnowledgeOnnxIsolatedProtocol.MAX_INPUT_RANK, -1),
                    data.getInt(KnowledgeOnnxIsolatedProtocol.MAX_OUTPUT_RANK, -1));
            if (millis >= 0 && millis <= KnowledgeOnnxIsolatedProtocol.WATCHDOG_MILLIS
                    && inputs >= 1 && inputs <= 32 && outputs >= 1 && outputs <= 32
                    && KnowledgeOnnxMemoryTrace.valid(before, observed, after)
                    && KnowledgeOnnxIoSummary.valid(io, inputs, outputs)
                    && KnowledgeOnnxRssHighWater.valid(rssHighWater)) {
                report = new KnowledgeOnnxSessionGate.Report(
                        KnowledgeOnnxIsolatedProtocol.safeVersion(
                                data.getString(KnowledgeOnnxIsolatedProtocol.VERSION)),
                        millis, data.getLong(KnowledgeOnnxIsolatedProtocol.NATIVE_DELTA, 0),
                        inputs, outputs, io,
                        new KnowledgeOnnxMemoryTrace(before, observed, after),
                        rssHighWater);
            }
        }
        final Callback done = finish();
        if (done != null) {
            if (report != null) done.success(report);
            else done.failure();
        }
        return true;
    }

    private void notifyFailure() {
        final Callback done = callback;
        cancel();
        if (done != null) done.failure();
    }

    void cancel() {
        if (remote != null) {
            try {
                remote.send(Message.obtain(null,
                        KnowledgeOnnxIsolatedProtocol.CANCEL_AND_EXIT));
            } catch (RemoteException ignored) { }
        }
        finish();
    }

    private Callback finish() {
        mainHandler.removeCallbacks(deadline);
        final Callback done = callback;
        callback = null;
        remote = null;
        if (activeBinder != null && binderDeath != null) {
            activeBinder.unlinkToDeath(binderDeath, 0);
        }
        activeBinder = null;
        binderDeath = null;
        final ServiceConnection old = connection;
        connection = null;
        closePipe(writePipe);
        writePipe = null;
        if (senderThread != null) {
            senderThread.interrupt();
            senderThread = null;
            // Writer owns and wipes the bytes after its blocked write unblocks.
        } else if (pendingBytes != null) {
            Arrays.fill(pendingBytes, (byte) 0);
        }
        pendingBytes = null;
        if (old != null && bound) {
            try {
                context.unbindService(old);
            } catch (IllegalArgumentException ignored) { }
        }
        bound = false;
        return done;
    }

    private static void closePipe(final ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) { }
    }
}
