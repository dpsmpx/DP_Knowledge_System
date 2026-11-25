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
import android.os.RemoteException;

/** One manual synthetic request at a time; no model bytes cross Binder. */
final class KnowledgeOnnxIsolatedClient {
    interface Callback {
        void success(KnowledgeOnnxSmoke.Report report);
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
    private boolean bound;
    private int nextRequestId;
    private int activeRequestId;
    private final Runnable clientDeadline = this::notifyFailure;

    KnowledgeOnnxIsolatedClient(final Context owner) {
        context = owner;
        mainHandler = new Handler(Looper.getMainLooper());
        inbox = new Messenger(new Handler(Looper.getMainLooper(), this::handleResponse));
    }

    boolean start(final Callback result) {
        if (result == null || connection != null) return false;
        callback = result;
        activeRequestId = ++nextRequestId;
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
                final Message request = Message.obtain(null,
                        KnowledgeOnnxIsolatedProtocol.RUN_SYNTHETIC);
                request.arg1 = requestId;
                request.replyTo = inbox;
                try {
                    remote.send(request);
                } catch (RemoteException invalid) {
                    notifyFailure();
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
                connection = null;
                callback = null;
            } else {
                mainHandler.postDelayed(clientDeadline,
                        KnowledgeOnnxIsolatedProtocol.WATCHDOG_MILLIS + 5_000L);
            }
            return bound;
        } catch (RuntimeException blocked) {
            connection = null;
            callback = null;
            bound = false;
            return false;
        }
    }

    /** The isolated process can kill itself even if a native run is still blocked. */
    void cancel() {
        if (remote != null) {
            try {
                remote.send(Message.obtain(null,
                        KnowledgeOnnxIsolatedProtocol.CANCEL_AND_EXIT));
            } catch (RemoteException ignored) { }
        }
        finish();
    }

    private boolean handleResponse(final Message response) {
        if (callback == null || connection == null) return true;
        if (response.arg1 != activeRequestId) return true;
        if (response.what != KnowledgeOnnxIsolatedProtocol.RESULT_PASSED
                && response.what != KnowledgeOnnxIsolatedProtocol.RESULT_FAILED) return true;
        KnowledgeOnnxSmoke.Report report = null;
        if (response.what == KnowledgeOnnxIsolatedProtocol.RESULT_PASSED) {
            final Bundle data = response.getData();
            final long init = data.getLong(KnowledgeOnnxIsolatedProtocol.INIT_MILLIS, -1L);
            final long run = data.getLong(KnowledgeOnnxIsolatedProtocol.RUN_MICROS, -1L);
            if (KnowledgeOnnxIsolatedProtocol.validTimings(init, run)) {
                report = new KnowledgeOnnxSmoke.Report(
                        KnowledgeOnnxIsolatedProtocol.safeVersion(
                                data.getString(KnowledgeOnnxIsolatedProtocol.VERSION)),
                        init, run, data.getLong(KnowledgeOnnxIsolatedProtocol.NATIVE_DELTA, 0L));
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
        final Callback done = finish();
        if (done != null) done.failure();
    }

    private Callback finish() {
        mainHandler.removeCallbacks(clientDeadline);
        final Callback done = callback;
        callback = null;
        remote = null;
        if (activeBinder != null && binderDeath != null) {
            activeBinder.unlinkToDeath(binderDeath, 0);
        }
        activeBinder = null;
        binderDeath = null;
        final ServiceConnection previous = connection;
        connection = null;
        if (previous != null && bound) {
            try {
                context.unbindService(previous);
            } catch (IllegalArgumentException ignored) { }
        }
        bound = false;
        return done;
    }
}
