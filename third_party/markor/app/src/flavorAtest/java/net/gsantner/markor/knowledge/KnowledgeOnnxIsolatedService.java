/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DPKS-only isolated process: no notes, model paths, weights or network input.
 * A separate, permissionless UID is enforced by the flavor manifest.
 */
public final class KnowledgeOnnxIsolatedService extends Service {
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Handler handler;
    private Messenger messenger;
    private final Runnable deadline = this::exitSandboxProcess;

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper(), this::handleMessage);
        messenger = new Messenger(handler);
    }

    @Override public IBinder onBind(final Intent intent) {
        return messenger.getBinder();
    }

    private boolean handleMessage(final Message message) {
        if (message.what == KnowledgeOnnxIsolatedProtocol.CANCEL_AND_EXIT) {
            exitSandboxProcess();
            return true;
        }
        if (message.what != KnowledgeOnnxIsolatedProtocol.RUN_SYNTHETIC) return true;
        final Messenger reply = message.replyTo;
        final int requestId = message.arg1;
        if (reply == null || !running.compareAndSet(false, true)) {
            answer(reply, requestId, null);
            return true;
        }
        handler.postDelayed(deadline, KnowledgeOnnxIsolatedProtocol.WATCHDOG_MILLIS);
        worker.execute(() -> {
            KnowledgeOnnxSmoke.Report report = null;
            try {
                report = KnowledgeOnnxCpuBackend.smoke();
                if (!KnowledgeOnnxIsolatedProtocol.validTimings(
                        report.sessionInitMillis, report.inferenceMicros)) report = null;
            } catch (Exception | LinkageError ignored) {
                // Native error text and paths never leave the worker process.
            } finally {
                answer(reply, requestId, report);
                handler.post(() -> {
                    handler.removeCallbacks(deadline);
                    stopSelf();
                });
            }
        });
        return true;
    }

    private static void answer(final Messenger recipient, final int requestId,
                               final KnowledgeOnnxSmoke.Report result) {
        if (recipient == null) return;
        final Message reply = Message.obtain(null, result == null
                ? KnowledgeOnnxIsolatedProtocol.RESULT_FAILED
                : KnowledgeOnnxIsolatedProtocol.RESULT_PASSED);
        reply.arg1 = requestId;
        if (result != null) {
            final Bundle data = new Bundle();
            data.putString(KnowledgeOnnxIsolatedProtocol.VERSION,
                    KnowledgeOnnxIsolatedProtocol.safeVersion(result.runtimeVersion));
            data.putLong(KnowledgeOnnxIsolatedProtocol.INIT_MILLIS, result.sessionInitMillis);
            data.putLong(KnowledgeOnnxIsolatedProtocol.RUN_MICROS, result.inferenceMicros);
            data.putLong(KnowledgeOnnxIsolatedProtocol.NATIVE_DELTA, result.nativeHeapDeltaBytes);
            reply.setData(data);
        }
        try {
            recipient.send(reply);
        } catch (RemoteException ignored) {
            // User cancelled/unbound or Android terminated the owner process.
        }
    }

    /** Stop ONLY this named worker process, never the main DPKS process. */
    private void exitSandboxProcess() {
        final ActivityManager.RunningAppProcessInfo self =
                new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(self);
        if (self.processName != null && self.processName.endsWith(":dpks_onnx_cpu")) {
            Process.killProcess(Process.myPid());
        } else {
            worker.shutdownNow();
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        if (handler != null) handler.removeCallbacks(deadline);
        worker.shutdownNow();
        super.onDestroy();
        // Bound-only service: do not keep a native worker alive after unbind.
        exitSandboxProcess();
    }
}
