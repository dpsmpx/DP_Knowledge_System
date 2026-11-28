/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;

import net.gsantner.markor.BuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DPKS-only isolated process: never sees note text, model paths or network input.
 * Real ONNX bytes enter only after separate manual consent through a bounded FD pipe.
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
        if (message.what == KnowledgeOnnxIsolatedProtocol.DEBUG_STALL) {
            // Instrumentation-only. A synthetic Java wait exercises Binder death,
            // cancellation and the process watchdog, never the native ONNX engine.
            if (!BuildConfig.DEBUG || message.replyTo == null
                    || !running.compareAndSet(false, true)) return true;
            final Messenger reply = message.replyTo;
            handler.postDelayed(deadline, KnowledgeOnnxIsolatedProtocol.DEBUG_WATCHDOG_MILLIS);
            worker.execute(() -> {
                try {
                    reply.send(Message.obtain(null,
                            KnowledgeOnnxIsolatedProtocol.DEBUG_STALL_STARTED));
                } catch (RemoteException ignored) { }
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            return true;
        }
        final boolean debugE5Fd = BuildConfig.DEBUG
                && message.what == KnowledgeOnnxIsolatedProtocol.RUN_DEBUG_E5_FD;
        final boolean debugDirectE5 = BuildConfig.DEBUG
                && message.what == KnowledgeOnnxIsolatedProtocol.DEBUG_INSPECT_DIRECT_E5_FD;
        final boolean debugLargeFd = BuildConfig.DEBUG
                && message.what == KnowledgeOnnxIsolatedProtocol.DEBUG_VERIFY_LARGE_FD;
        final boolean verifyOnlyFd =
                message.what == KnowledgeOnnxIsolatedProtocol.VERIFY_ONLY_LARGE_FD;
        final boolean sessionRequest = message.what == KnowledgeOnnxIsolatedProtocol.RUN_MODEL_SESSION
                || debugE5Fd || debugDirectE5;
        final boolean fdRequest = sessionRequest || debugLargeFd || verifyOnlyFd;
        final boolean debugOom = message.what == KnowledgeOnnxIsolatedProtocol.DEBUG_SIMULATE_OOM;
        if ((debugOom && !BuildConfig.DEBUG) || (!fdRequest
                && message.what != KnowledgeOnnxIsolatedProtocol.RUN_SYNTHETIC && !debugOom)) {
            return true;
        }
        final Messenger reply = message.replyTo;
        final int requestId = message.arg1;
        ParcelFileDescriptor pipe = null;
        String expectedSha = null;
        boolean simulatedLowMemory = false;
        int debugVariant = 0;
        long debugLargeLength = -1L;
        long verifyOnlyLength = -1L;
        if (fdRequest) {
            try {
                final Bundle request = message.getData();
                pipe = request.getParcelable(KnowledgeOnnxIsolatedProtocol.PIPE_FD);
                expectedSha = request.getString(KnowledgeOnnxIsolatedProtocol.MODEL_SHA256);
                if (debugLargeFd) {
                    debugLargeLength = request.getLong(
                            KnowledgeOnnxIsolatedProtocol.DEBUG_LARGE_FD_LENGTH, -1L);
                }
                if (verifyOnlyFd) {
                    verifyOnlyLength = request.getLong(
                            KnowledgeOnnxIsolatedProtocol.VERIFY_ONLY_FD_LENGTH, -1L);
                }
                simulatedLowMemory = BuildConfig.DEBUG && request.getBoolean(
                        KnowledgeOnnxIsolatedProtocol.DEBUG_FORCE_LOW_MEMORY, false);
                if (debugE5Fd || debugDirectE5) {
                    debugVariant = request.getInt(KnowledgeOnnxIsolatedProtocol.DEBUG_E5_VARIANT, 0);
                }
            } catch (RuntimeException invalidParcel) {
                closePipe(pipe);
                if (verifyOnlyFd) answerVerifyOnly(reply, requestId, false);
                else if (debugLargeFd) answerLargeFd(reply, requestId, false);
                else answerSession(reply, requestId, null);
                return true;
            }
            if (pipe == null || !KnowledgeOnnxSessionPipe.validSha256(expectedSha)
                    || (debugLargeFd && !KnowledgeE5LargeFdFixture.isPinned(
                            debugLargeLength, expectedSha))
                    || (verifyOnlyFd && !KnowledgeOnnxIsolatedProtocol.validVerifyOnlyFdEnvelope(
                            verifyOnlyLength, expectedSha))
                    || (sessionRequest && (message.arg2 < 16
                            || message.arg2 > KnowledgeOnnxSessionGate.MAX_MODEL_BYTES))
                    || (debugE5Fd && !KnowledgeOnnxIsolatedProtocol.validDebugE5Variant(debugVariant))
                    || (debugDirectE5 && !KnowledgeE5IsolatedSmoke.isPinnedDirectEnvelope(
                            message.arg2, expectedSha, debugVariant))) {
                closePipe(pipe);
                if (verifyOnlyFd) answerVerifyOnly(reply, requestId, false);
                else if (debugLargeFd) answerLargeFd(reply, requestId, false);
                else answerSession(reply, requestId, null);
                return true;
            }
        }
        if (reply == null || !running.compareAndSet(false, true)) {
            closePipe(pipe);
            if (verifyOnlyFd) answerVerifyOnly(reply, requestId, false);
            else if (debugLargeFd) answerLargeFd(reply, requestId, false);
            else if (sessionRequest) answerSession(reply, requestId, null);
            else answer(reply, requestId, null);
            return true;
        }
        final ParcelFileDescriptor inputPipe = pipe;
        final String sha = expectedSha;
        final boolean debugLowMemory = simulatedLowMemory;
        final int variant = debugVariant;
        final long largeLength = verifyOnlyFd ? verifyOnlyLength : debugLargeLength;
        final int size = message.arg2;
        handler.postDelayed(deadline, verifyOnlyFd
                ? KnowledgeOnnxIsolatedProtocol.VERIFY_ONLY_WATCHDOG_MILLIS
                : KnowledgeOnnxIsolatedProtocol.WATCHDOG_MILLIS);
        worker.execute(() -> {
            KnowledgeOnnxSmoke.Report smoke = null;
            KnowledgeOnnxSessionGate.Report session = null;
            boolean memoryDenied = false;
            boolean fatalMemory = false;
            boolean largeVerified = false;
            try {
                final KnowledgeOnnxMemoryTrace.Recorder memory =
                        new KnowledgeOnnxMemoryTrace.Recorder();
                memory.begin(KnowledgeOnnxWorkerMemory.samplePssKiB());
                if (debugOom) {
                    // Exercise the same fatal-memory handler as production ONNX work,
                    // without exhausting RAM or opening a user-supplied model.
                    throw new OutOfMemoryError("debug-only simulated worker OOM");
                } else if (debugLargeFd || verifyOnlyFd) {
                    // Never open ORT, filesystem paths or note data. Only the
                    // pinned synthetic debug fixture exercises a direct-memory
                    // quarantine; the real manual E5 verify-only command MUST
                    // remain streaming and must not allocate model-sized RAM.
                    if (debugLargeFd) {
                        // Strict admission BEFORE creating a model-sized native
                        // direct buffer; a synthetic low-RAM flag exercises this
                        // path without ever allocating fixture bytes.
                        KnowledgeOnnxWorkerMemory.requireAvailableForDirect(
                                this, largeLength, false, debugLowMemory);
                    } else {
                        // Real manual verify-only still streams using 64 KiB,
                        // with no direct/model-sized allocation or ORT session.
                        KnowledgeOnnxWorkerMemory.requireAvailable(this, false);
                    }
                    try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(inputPipe)) {
                        if (debugLargeFd) {
                            try (KnowledgeE5DirectModelBuffer.Verified direct =
                                         KnowledgeE5DirectModelBuffer.readVerified(
                                                 stream, largeLength, sha)) {
                                // SHA and exact EOF must match before exposing a
                                // direct, read-only view. Do not pass this
                                // non-ONNX fixture to the native runtime.
                                if (!direct.view().isDirect() || !direct.view().isReadOnly()
                                        || direct.view().remaining() != largeLength) {
                                    throw new IOException("Invalid synthetic E5 buffer");
                                }
                            }
                        } else {
                            // Announced SHA alone does not prove provenance;
                            // the sender also audits its installed package.
                            KnowledgeE5LargeFdTransfer.verifyExactly(stream, largeLength, sha);
                        }
                    }
                    largeVerified = true;
                } else if (debugDirectE5) {
                    // DEBUG-only, two worker-pinned arithmetic E5 fixtures.
                    // Exact SHA/size are pinned by the envelope and rechecked
                    // on the FD. No received-FD heap copy or session.run();
                    // the tiny independent pinned reference is regenerated.
                    KnowledgeOnnxWorkerMemory.requireAvailableForDirect(
                            this, size, false, debugLowMemory);
                    try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(inputPipe);
                         KnowledgeE5DirectModelBuffer.Verified direct =
                                 KnowledgeE5DirectModelBuffer.readVerified(stream, size, sha)) {
                        // Independent byte comparison BEFORE any native ORT.
                        KnowledgeE5IsolatedSmoke.requirePinnedDirectGraph(direct, variant);
                        memory.observe(KnowledgeOnnxWorkerMemory.samplePssKiB());
                        session = KnowledgeOnnxCpuBackend.inspectVerifiedDirectDebug(
                                this, direct, memory);
                        if (!validSessionReport(session)) session = null;
                    }
                } else if (sessionRequest) {
                    // Check in the worker immediately before allocating model bytes,
                    // then once more before native session creation. Neither check
                    // is a reservation and both are independent of the UI gate.
                    KnowledgeOnnxWorkerMemory.requireAvailable(this, false);
                    final byte[] model;
                    try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(inputPipe)) {
                        model = KnowledgeOnnxSessionPipe.readVerified(stream, size, sha);
                    }
                    try {
                        memory.observe(KnowledgeOnnxWorkerMemory.samplePssKiB());
                        KnowledgeOnnxWorkerMemory.requireAvailable(this, debugLowMemory);
                        // Unlike the manual model session (metadata only), the
                        // debug FD command may run ONLY one of two exact worker-generated
                        // arithmetic E5 fixtures, bound to the declared variant.
                        session = debugE5Fd
                                ? KnowledgeOnnxCpuBackend.runPinnedSyntheticE5(this, model,
                                        variant, memory)
                                : KnowledgeOnnxCpuBackend.inspect(model, memory);
                        if (!validSessionReport(session)) session = null;
                    } finally {
                        Arrays.fill(model, (byte) 0);
                    }
                } else {
                    KnowledgeOnnxWorkerMemory.requireAvailable(this, false);
                    smoke = KnowledgeOnnxCpuBackend.smoke(this, memory);
                    if (!KnowledgeOnnxIsolatedProtocol.validTimings(
                            smoke.sessionInitMillis, smoke.inferenceMicros)) smoke = null;
                }
            } catch (KnowledgeOnnxWorkerMemory.Denied denied) {
                memoryDenied = true;
            } catch (OutOfMemoryError exhausted) {
                // After Java/native allocation failure, the worker must not be reused.
                // Let the owning client observe Binder death and fail this manual run.
                fatalMemory = true;
                exitSandboxProcess();
            } catch (Exception | LinkageError ignored) {
                // Native error text and paths never leave the worker process.
            } finally {
                closePipe(inputPipe);
                if (!fatalMemory) {
                    if (verifyOnlyFd) answerVerifyOnly(reply, requestId, largeVerified);
                    else if (debugLargeFd) answerLargeFd(reply, requestId, largeVerified);
                    else if (sessionRequest) answerSession(reply, requestId, session, memoryDenied);
                    else answer(reply, requestId, smoke);
                    handler.post(() -> {
                        handler.removeCallbacks(deadline);
                        stopSelf();
                    });
                }
            }
        });
        return true;
    }

    private static boolean validSessionReport(final KnowledgeOnnxSessionGate.Report session) {
        return session != null && session.sessionInitMillis >= 0
                && session.sessionInitMillis <= KnowledgeOnnxIsolatedProtocol.WATCHDOG_MILLIS
                && session.inputs >= 1 && session.inputs <= KnowledgeOnnxIoSummary.MAX_IO
                && session.outputs >= 1 && session.outputs <= KnowledgeOnnxIoSummary.MAX_IO
                && KnowledgeOnnxIoSummary.valid(
                        session.ioSummary, session.inputs, session.outputs);
    }

    private static void answerSession(final Messenger recipient, final int requestId,
                                      final KnowledgeOnnxSessionGate.Report result) {
        answerSession(recipient, requestId, result, false);
    }

    /** A single debug-only Boolean; no file bytes, paths or session metadata cross Binder. */
    private static void answerLargeFd(final Messenger recipient, final int requestId,
                                      final boolean verified) {
        if (recipient == null) return;
        final Message response = Message.obtain(null, verified
                ? KnowledgeOnnxIsolatedProtocol.RESULT_DEBUG_LARGE_FD_VERIFIED
                : KnowledgeOnnxIsolatedProtocol.RESULT_DEBUG_LARGE_FD_FAILED);
        response.arg1 = requestId;
        try {
            recipient.send(response);
        } catch (RemoteException ignored) { }
    }

    /** SHA equality ONLY: deliberately no trust/capability, native or path output. */
    private static void answerVerifyOnly(final Messenger recipient, final int requestId,
                                         final boolean matched) {
        if (recipient == null) return;
        final Message result = Message.obtain(null, matched
                ? KnowledgeOnnxIsolatedProtocol.RESULT_VERIFY_ONLY_MATCH
                : KnowledgeOnnxIsolatedProtocol.RESULT_VERIFY_ONLY_MISMATCH);
        result.arg1 = requestId;
        try {
            recipient.send(result);
        } catch (RemoteException ignored) { }
    }

    private static void answerSession(final Messenger recipient, final int requestId,
                                      final KnowledgeOnnxSessionGate.Report result,
                                      final boolean memoryDenied) {
        if (recipient == null) return;
        final Message reply = Message.obtain(null, memoryDenied
                ? KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_MEMORY_DENIED
                : result == null ? KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_FAILED
                : KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_PASSED);
        reply.arg1 = requestId;
        if (result != null) {
            final Bundle data = new Bundle();
            data.putString(KnowledgeOnnxIsolatedProtocol.VERSION,
                    KnowledgeOnnxIsolatedProtocol.safeVersion(result.runtimeVersion));
            data.putLong(KnowledgeOnnxIsolatedProtocol.INIT_MILLIS, result.sessionInitMillis);
            data.putLong(KnowledgeOnnxIsolatedProtocol.NATIVE_DELTA,
                    result.nativeHeapDeltaBytes);
            data.putLong(KnowledgeOnnxIsolatedProtocol.INPUTS, result.inputs);
            data.putLong(KnowledgeOnnxIsolatedProtocol.OUTPUTS, result.outputs);
            final KnowledgeOnnxIoSummary io = result.ioSummary;
            data.putInt(KnowledgeOnnxIsolatedProtocol.INPUT_TENSORS, io.inputTensors);
            data.putInt(KnowledgeOnnxIsolatedProtocol.OUTPUT_TENSORS, io.outputTensors);
            data.putInt(KnowledgeOnnxIsolatedProtocol.FLOAT_INPUTS, io.floatInputs);
            data.putInt(KnowledgeOnnxIsolatedProtocol.FLOAT_OUTPUTS, io.floatOutputs);
            data.putInt(KnowledgeOnnxIsolatedProtocol.INT64_INPUTS, io.int64Inputs);
            data.putInt(KnowledgeOnnxIsolatedProtocol.INT64_OUTPUTS, io.int64Outputs);
            data.putInt(KnowledgeOnnxIsolatedProtocol.DYNAMIC_INPUT_AXES, io.dynamicInputAxes);
            data.putInt(KnowledgeOnnxIsolatedProtocol.DYNAMIC_OUTPUT_AXES, io.dynamicOutputAxes);
            data.putInt(KnowledgeOnnxIsolatedProtocol.MAX_INPUT_RANK, io.maxInputRank);
            data.putInt(KnowledgeOnnxIsolatedProtocol.MAX_OUTPUT_RANK, io.maxOutputRank);
            if (io.e5DescriptorSha256 != null) {
                data.putString(KnowledgeOnnxIsolatedProtocol.E5_DESCRIPTOR_SHA256,
                        io.e5DescriptorSha256);
            }
            putMemory(data, result.workerMemory, result.workerRssHighWaterKiB);
            reply.setData(data);
        }
        try {
            recipient.send(reply);
        } catch (RemoteException ignored) { }
    }

    private static void closePipe(final ParcelFileDescriptor pipe) {
        if (pipe == null) return;
        try {
            pipe.close();
        } catch (IOException ignored) { }
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
            putMemory(data, result.workerMemory, result.workerRssHighWaterKiB);
            reply.setData(data);
        }
        try {
            recipient.send(reply);
        } catch (RemoteException ignored) {
            // User cancelled/unbound or Android terminated the owner process.
        }
    }

    private static void putMemory(final Bundle data, final KnowledgeOnnxMemoryTrace memory,
                                  final long rssHighWaterKiB) {
        data.putLong(KnowledgeOnnxIsolatedProtocol.PSS_BEFORE_KIB, memory.beforeKiB);
        data.putLong(KnowledgeOnnxIsolatedProtocol.PSS_OBSERVED_KIB, memory.maxObservedKiB);
        data.putLong(KnowledgeOnnxIsolatedProtocol.PSS_AFTER_KIB, memory.afterCloseKiB);
        data.putLong(KnowledgeOnnxIsolatedProtocol.RSS_HIGH_WATER_KIB, rssHighWaterKiB);
    }

    /** Stop ONLY this named worker process, never the main DPKS process. */
    private void exitSandboxProcess() {
        if (KnowledgeOnnxProcessIdentity.isWorker()) {
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
