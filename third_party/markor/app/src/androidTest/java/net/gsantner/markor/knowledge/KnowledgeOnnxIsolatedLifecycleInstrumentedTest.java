/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Explicit CI-only, synthetic Android Binder/FD/native isolation checks.
 * Never reads an installed model, user's vault, journal, or a network endpoint.
 * This is emulator QA, not evidence that a physical phone will behave identically.
 */
@RunWith(AndroidJUnit4.class)
public final class KnowledgeOnnxIsolatedLifecycleInstrumentedTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void manifestWorkerIsPrivateAndIsolated() throws Exception {
        assertEquals("com.dpsmpx.dpks", context.getPackageName());
        assertFalse(KnowledgeOnnxProcessIdentity.isWorker());
        assertTrue(KnowledgeOnnxProcessIdentity.workerName(
                "com.dpsmpx.dpks:dpks_onnx_cpu:net.gsantner.markor.knowledge.KnowledgeOnnxIsolatedService"));
        assertTrue(KnowledgeOnnxProcessIdentity.workerName(
                "com.dpsmpx.dpks:dpks_onnx_cpu"));
        assertFalse(KnowledgeOnnxProcessIdentity.workerName(
                "com.dpsmpx.dpks:dpks_onnx_cpu_fake"));
        final ServiceInfo service = context.getPackageManager().getServiceInfo(
                new ComponentName(context, KnowledgeOnnxIsolatedService.class), 0);
        assertFalse(service.exported);
        assertTrue((service.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0);
        assertTrue(service.processName.endsWith(":dpks_onnx_cpu"));
    }

    @Test public void identityAnd384E5SmokesCanRunTwiceAcrossWorkerLifecycle()
            throws Exception {
        final KnowledgeOnnxIsolatedClient client = new KnowledgeOnnxIsolatedClient(context);
        try {
            for (int run = 0; run < 2; run++) {
                final CountDownLatch complete = new CountDownLatch(1);
                final AtomicReference<KnowledgeOnnxSmoke.Report> report = new AtomicReference<>();
                final AtomicBoolean bound = new AtomicBoolean();
                instrumentation.runOnMainSync(() -> bound.set(client.start(
                        new KnowledgeOnnxIsolatedClient.Callback() {
                    @Override public void success(final KnowledgeOnnxSmoke.Report result) {
                        report.set(result);
                        complete.countDown();
                    }
                    @Override public void failure() { complete.countDown(); }
                })));
                assertTrue("Synthetic worker bind failed", bound.get());
                assertTrue("Synthetic worker did not respond", complete.await(40, TimeUnit.SECONDS));
                // A successful worker report now also implies two independent
                // 384-channel E5 synthetic variants completed inside this UID.
                assertNotNull("Generated Identity/E5 CPU worker test failed", report.get());
                assertTrue(report.get().sessionInitMillis >= 0);
                assertTrue(report.get().inferenceMicros >= 0);
                assertWorkerPssSnapshots(report.get().workerMemory);
                assertTrue("Worker kernel RSS high-water is not bounded",
                        KnowledgeOnnxRssHighWater.valid(report.get().workerRssHighWaterKiB));
                // Give the unbound isolated process a moment to exit before rebinding.
                if (run == 0) Thread.sleep(1000);
            }
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void syntheticFdSessionUsesWorkerAndVerifiesOneInputOutput()
            throws Exception {
        final KnowledgeOnnxIsolatedSessionClient client =
                new KnowledgeOnnxIsolatedSessionClient(context);
        final byte[] graph = KnowledgeOnnxSmoke.identityModel();
        // Compare the native report with a fresh offline parse of the SAME
        // generated bytes. This never opens installed or user-owned models.
        final File staged = File.createTempFile("dpks-generated-identity-", ".onnx",
                context.getCacheDir());
        final KnowledgeModelContainerProbe.Result declared;
        try {
            try (FileOutputStream output = new FileOutputStream(staged)) {
                output.write(graph);
            }
            declared = KnowledgeModelContainerProbe.inspect(
                    staged, KnowledgeModelFiles.Format.ONNX_UNVERIFIED);
        } finally {
            assertTrue("Generated preflight fixture was not removed", staged.delete());
        }
        final String sha = KnowledgeOnnxSessionPipe.sha256Hex(graph);
        final CountDownLatch complete = new CountDownLatch(1);
        final AtomicReference<KnowledgeOnnxSessionGate.Report> report = new AtomicReference<>();
        final AtomicBoolean bound = new AtomicBoolean();
        try {
            instrumentation.runOnMainSync(() -> bound.set(client.start(graph, sha,
                    new KnowledgeOnnxIsolatedSessionClient.Callback() {
                @Override public void success(final KnowledgeOnnxSessionGate.Report result) {
                    report.set(result);
                    complete.countDown();
                }
                @Override public void failure() { complete.countDown(); }
            })));
            assertTrue("FD session bind failed", bound.get());
            assertTrue("FD session worker did not respond", complete.await(40, TimeUnit.SECONDS));
            assertNotNull("FD session verification/native open failed", report.get());
            assertEquals(1, report.get().inputs);
            assertEquals(1, report.get().outputs);
            final KnowledgeOnnxIoSummary io = report.get().ioSummary;
            assertTrue("Native ONNX I/O summary failed its IPC bounds",
                    KnowledgeOnnxIoSummary.valid(io, report.get().inputs, report.get().outputs));
            assertEquals("Identity must expose one float32 input tensor", 1, io.floatInputs);
            assertEquals("Identity must expose one float32 output tensor", 1, io.floatOutputs);
            assertEquals(1, io.inputTensors);
            assertEquals(1, io.outputTensors);
            assertEquals(0, io.int64Inputs);
            assertEquals(0, io.int64Outputs);
            assertEquals(0, io.dynamicInputAxes);
            assertEquals(0, io.dynamicOutputAxes);
            assertEquals(1, io.maxInputRank);
            assertEquals(1, io.maxOutputRank);
            assertNull("Unrelated Identity graph must not claim E5", io.e5DescriptorSha256);
            assertEquals(1, declared.floatInputs);
            assertEquals(1, declared.floatOutputs);
            assertEquals(0, declared.int64Inputs);
            assertEquals(0, declared.int64Outputs);
            assertEquals("Offline GraphProto and native I/O counts diverged for Identity",
                    KnowledgeOnnxIoAgreement.Verdict.COUNTS_AND_TENSORS_AGREE,
                    KnowledgeOnnxIoAgreement.compare(declared.graphInputs,
                            declared.graphOutputs, declared.typedInputs, declared.typedOutputs,
                            declared.floatInputs, declared.floatOutputs,
                            declared.int64Inputs, declared.int64Outputs,
                            report.get().inputs, report.get().outputs, io));
            assertTrue(report.get().sessionInitMillis >= 0);
            assertWorkerPssSnapshots(report.get().workerMemory);
            assertTrue("Worker kernel RSS high-water is not bounded",
                    KnowledgeOnnxRssHighWater.valid(report.get().workerRssHighWaterKiB));
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void debugFdPinnedE5RunsAll384ChannelsInsideWorker() throws Exception {
        final KnowledgeOnnxIsolatedSessionClient client =
                new KnowledgeOnnxIsolatedSessionClient(context);
        // Generated arithmetic graph; no weights, installed file or note bytes.
        final byte[] graph = KnowledgeE5TokenEmbeddingSmoke.graph(6, false, true);
        final String sha = KnowledgeOnnxSessionPipe.sha256Hex(graph);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<KnowledgeOnnxSessionGate.Report> report = new AtomicReference<>();
        final AtomicBoolean bound = new AtomicBoolean();
        try {
            instrumentation.runOnMainSync(() -> bound.set(client.startDebugSyntheticE5(
                    graph, sha, KnowledgeOnnxIsolatedProtocol.DEBUG_E5_TWO_INPUT_POOLER,
                    new KnowledgeOnnxIsolatedSessionClient.Callback() {
                @Override public void success(final KnowledgeOnnxSessionGate.Report result) {
                    report.set(result);
                    finished.countDown();
                }
                @Override public void failure() { finished.countDown(); }
            })));
            assertTrue("Generated E5 FD bind failed", bound.get());
            assertTrue("Generated E5 FD worker timed out", finished.await(40, TimeUnit.SECONDS));
            assertNotNull("Pinned E5 FD/ORT/masked-mean verification failed", report.get());
            // Pooler is deliberately unrelated; the runner must select the
            // named hidden-state output and compare each of its 384 channels.
            assertEquals(2, report.get().inputs);
            assertEquals(2, report.get().outputs);
            final KnowledgeOnnxIoSummary io = report.get().ioSummary;
            assertEquals(2, io.inputTensors);
            assertEquals(2, io.outputTensors);
            assertEquals(2, io.int64Inputs);
            assertEquals(2, io.floatOutputs);
            assertEquals(2, io.maxInputRank);
            assertEquals(3, io.maxOutputRank);
            assertEquals("Two-input E5 native metadata fingerprint differs",
                    expectedE5DescriptorSha256(false, true), io.e5DescriptorSha256);
            assertWorkerPssSnapshots(report.get().workerMemory);
            assertTrue(KnowledgeOnnxRssHighWater.valid(report.get().workerRssHighWaterKiB));
            for (final byte wiped : graph) assertEquals("Sent E5 bytes not wiped", 0, wiped);
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void debugFdPinnedThreeInputE5RunsAll384ChannelsInsideWorker()
            throws Exception {
        final KnowledgeOnnxIsolatedSessionClient client =
                new KnowledgeOnnxIsolatedSessionClient(context);
        final byte[] graph = KnowledgeE5TokenEmbeddingSmoke.graph(6, true, false);
        final String sha = KnowledgeOnnxSessionPipe.sha256Hex(graph);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<KnowledgeOnnxSessionGate.Report> report = new AtomicReference<>();
        final AtomicBoolean bound = new AtomicBoolean();
        try {
            instrumentation.runOnMainSync(() -> bound.set(client.startDebugSyntheticE5(
                    graph, sha, KnowledgeOnnxIsolatedProtocol.DEBUG_E5_THREE_INPUT,
                    new KnowledgeOnnxIsolatedSessionClient.Callback() {
                @Override public void success(final KnowledgeOnnxSessionGate.Report result) {
                    report.set(result);
                    finished.countDown();
                }
                @Override public void failure() { finished.countDown(); }
            })));
            assertTrue("Three-input E5 FD bind failed", bound.get());
            assertTrue("Three-input E5 FD worker timed out", finished.await(40, TimeUnit.SECONDS));
            assertNotNull("Pinned 3-input E5 failed its 384-channel worker verification",
                    report.get());
            assertEquals(3, report.get().inputs);
            assertEquals(1, report.get().outputs);
            final KnowledgeOnnxIoSummary io = report.get().ioSummary;
            assertEquals(3, io.inputTensors);
            assertEquals(1, io.outputTensors);
            assertEquals(3, io.int64Inputs);
            assertEquals(1, io.floatOutputs);
            assertEquals(2, io.maxInputRank);
            assertEquals(3, io.maxOutputRank);
            assertEquals("Three-input E5 native metadata fingerprint differs",
                    expectedE5DescriptorSha256(true, false), io.e5DescriptorSha256);
            assertWorkerPssSnapshots(report.get().workerMemory);
            assertTrue(KnowledgeOnnxRssHighWater.valid(report.get().workerRssHighWaterKiB));
            for (final byte wiped : graph) assertEquals("Sent three-input E5 bytes not wiped", 0, wiped);
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void debugFdE5RejectsOtherValidGraphWithItsOwnCorrectSha() throws Exception {
        final KnowledgeOnnxIsolatedSessionClient client =
                new KnowledgeOnnxIsolatedSessionClient(context);
        // Also a valid generated E5 ONNX graph, but the claimed variant is wrong.
        // A correct sender-supplied SHA alone must never authorize native Run().
        final byte[] alternate = KnowledgeE5TokenEmbeddingSmoke.graph(6, true, false);
        final String matchingSha = KnowledgeOnnxSessionPipe.sha256Hex(alternate);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicBoolean unexpectedSuccess = new AtomicBoolean();
        final AtomicBoolean bound = new AtomicBoolean();
        try {
            instrumentation.runOnMainSync(() -> bound.set(client.startDebugSyntheticE5(
                    alternate, matchingSha,
                    KnowledgeOnnxIsolatedProtocol.DEBUG_E5_TWO_INPUT_POOLER,
                    new KnowledgeOnnxIsolatedSessionClient.Callback() {
                @Override public void success(final KnowledgeOnnxSessionGate.Report ignored) {
                    unexpectedSuccess.set(true);
                    finished.countDown();
                }
                @Override public void failure() { finished.countDown(); }
            })));
            assertTrue("Alternate E5 FD bind failed", bound.get());
            assertTrue("Alternate E5 FD rejection timed out", finished.await(40, TimeUnit.SECONDS));
            assertFalse("A self-hashed but unpinned graph reached native inference",
                    unexpectedSuccess.get());
            for (final byte wiped : alternate) assertEquals("Rejected E5 bytes not wiped", 0, wiped);
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void debugFdE5RejectsMutatedSelfHashedFixtureAndInvalidVariant()
            throws Exception {
        final KnowledgeOnnxIsolatedSessionClient client =
                new KnowledgeOnnxIsolatedSessionClient(context);
        // Mutating a known fixture and recomputing its SHA cannot make it trusted.
        final byte[] changed = KnowledgeE5TokenEmbeddingSmoke.graph(6, false, true);
        changed[changed.length / 2] ^= 1;
        final String recomputedSha = KnowledgeOnnxSessionPipe.sha256Hex(changed);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicBoolean unexpectedSuccess = new AtomicBoolean();
        final AtomicBoolean bound = new AtomicBoolean();
        try {
            instrumentation.runOnMainSync(() -> bound.set(client.startDebugSyntheticE5(
                    changed, recomputedSha,
                    KnowledgeOnnxIsolatedProtocol.DEBUG_E5_TWO_INPUT_POOLER,
                    new KnowledgeOnnxIsolatedSessionClient.Callback() {
                @Override public void success(final KnowledgeOnnxSessionGate.Report ignored) {
                    unexpectedSuccess.set(true);
                    finished.countDown();
                }
                @Override public void failure() { finished.countDown(); }
            })));
            assertTrue("Changed E5 FD bind failed", bound.get());
            assertTrue("Changed E5 FD rejection timed out", finished.await(40, TimeUnit.SECONDS));
            assertFalse("Mutated self-hashed graph reached native inference",
                    unexpectedSuccess.get());
            for (final byte wiped : changed) assertEquals("Mutated E5 bytes not wiped", 0, wiped);
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }

        // Reject unknown or missing fixture identifiers without even binding.
        final byte[] invalid = KnowledgeE5TokenEmbeddingSmoke.graph(6, false, true);
        final String sha = KnowledgeOnnxSessionPipe.sha256Hex(invalid);
        final AtomicBoolean invalidAccepted = new AtomicBoolean(true);
        instrumentation.runOnMainSync(() -> invalidAccepted.set(client.startDebugSyntheticE5(
                invalid, sha, 999, new KnowledgeOnnxIsolatedSessionClient.Callback() {
            @Override public void success(final KnowledgeOnnxSessionGate.Report ignored) {
                unexpectedSuccess.set(true);
            }
            @Override public void failure() { unexpectedSuccess.set(true); }
        })));
        assertFalse("Unrecognized E5 fixture identifier was accepted", invalidAccepted.get());
        for (final byte wiped : invalid) assertEquals("Invalid fixture bytes not wiped", 0, wiped);
        assertFalse("Invalid fixture request invoked callback", unexpectedSuccess.get());
    }

    @Test public void debugFdWorkerRejectsUnknownVariantWithoutReadingItsPipe()
            throws Exception {
        final byte[] graph = KnowledgeE5TokenEmbeddingSmoke.graph(6, false, true);
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendDebugE5(pipe[0], graph.length,
                    KnowledgeOnnxSessionPipe.sha256Hex(graph), 999);
            closePipe(pipe[0]);
            // Deliberately keep the write end open and do not send any bytes.
            // The invalid-variant response must precede even the first FD read.
            assertTrue("Unknown variant blocked on an unread FD",
                    connection.responded.await(5, TimeUnit.SECONDS));
            assertEquals("Unknown variant unexpectedly reached ONNX",
                    KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_FAILED,
                    connection.responseCode.get());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
            Arrays.fill(graph, (byte) 0);
        }
    }

    @Test public void simultaneousFdRequestIsRefusedWithoutBreakingActiveSession()
            throws Exception {
        final KnowledgeOnnxIsolatedSessionClient client =
                new KnowledgeOnnxIsolatedSessionClient(context);
        final byte[] first = KnowledgeOnnxSmoke.identityModel();
        final byte[] second = KnowledgeOnnxSmoke.identityModel();
        final String digest = KnowledgeOnnxSessionPipe.sha256Hex(first);
        final CountDownLatch complete = new CountDownLatch(1);
        final AtomicReference<KnowledgeOnnxSessionGate.Report> report = new AtomicReference<>();
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicBoolean firstAccepted = new AtomicBoolean();
        final AtomicBoolean secondAccepted = new AtomicBoolean(true);
        try {
            instrumentation.runOnMainSync(() -> {
                firstAccepted.set(client.start(first, digest,
                        new KnowledgeOnnxIsolatedSessionClient.Callback() {
                    @Override public void success(final KnowledgeOnnxSessionGate.Report result) {
                        report.set(result);
                        callbacks.incrementAndGet();
                        complete.countDown();
                    }
                    @Override public void failure() {
                        callbacks.incrementAndGet();
                        complete.countDown();
                    }
                }));
                secondAccepted.set(client.start(second, digest,
                        new KnowledgeOnnxIsolatedSessionClient.Callback() {
                    @Override public void success(final KnowledgeOnnxSessionGate.Report ignored) {
                        callbacks.incrementAndGet();
                    }
                    @Override public void failure() { callbacks.incrementAndGet(); }
                }));
            });
            assertTrue("First session must bind", firstAccepted.get());
            assertFalse("Parallel session unexpectedly bound", secondAccepted.get());
            for (final byte wiped : second) assertEquals("Rejected weights not wiped", 0, wiped);
            assertTrue("Active session timed out", complete.await(40, TimeUnit.SECONDS));
            assertNotNull("Parallel refusal interfered with first session", report.get());
            assertEquals("A rejected request must never invoke a callback", 1, callbacks.get());
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void cancellingPendingBindSilencesCallbacks() throws Exception {
        final KnowledgeOnnxIsolatedSessionClient client =
                new KnowledgeOnnxIsolatedSessionClient(context);
        final byte[] graph = KnowledgeOnnxSmoke.identityModel();
        final String sha = KnowledgeOnnxSessionPipe.sha256Hex(graph);
        final AtomicInteger unexpectedCallbacks = new AtomicInteger();
        final AtomicBoolean bound = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> {
            bound.set(client.start(graph, sha, new KnowledgeOnnxIsolatedSessionClient.Callback() {
                @Override public void success(final KnowledgeOnnxSessionGate.Report result) {
                    unexpectedCallbacks.incrementAndGet();
                }
                @Override public void failure() { unexpectedCallbacks.incrementAndGet(); }
            }));
            client.cancel();
        });
        assertTrue("Cancellation preflight never bound", bound.get());
        // A late onServiceConnected or reply must not resurrect a cancelled callback.
        Thread.sleep(1200);
        instrumentation.waitForIdleSync();
        assertEquals(0, unexpectedCallbacks.get());

        // A cancelled request owns and wipes its original bytes. A new manual
        // attempt uses fresh generated bytes and a new Binder generation.
        final byte[] retryGraph = KnowledgeOnnxSmoke.identityModel();
        final String retrySha = KnowledgeOnnxSessionPipe.sha256Hex(retryGraph);
        final CountDownLatch retried = new CountDownLatch(1);
        final AtomicReference<KnowledgeOnnxSessionGate.Report> retriedReport =
                new AtomicReference<>();
        try {
            instrumentation.runOnMainSync(() -> bound.set(client.start(retryGraph, retrySha,
                    new KnowledgeOnnxIsolatedSessionClient.Callback() {
                @Override public void success(final KnowledgeOnnxSessionGate.Report report) {
                    retriedReport.set(report);
                    retried.countDown();
                }
                @Override public void failure() { retried.countDown(); }
            })));
            assertTrue("Manual retry after cancel failed to bind", bound.get());
            assertTrue("Manual retry never completed", retried.await(40, TimeUnit.SECONDS));
            assertNotNull("Manual retry failed its generated ONNX session", retriedReport.get());
            assertEquals("Late cancelled request invoked a callback", 0,
                    unexpectedCallbacks.get());
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void syntheticBindTimeoutFailsOnceAndFreshRequestCanReconnect()
            throws Exception {
        final DelayedBindContext delayed = new DelayedBindContext(context);
        final KnowledgeOnnxIsolatedClient client = new KnowledgeOnnxIsolatedClient(delayed, 350L);
        final CountDownLatch expired = new CountDownLatch(1);
        final AtomicInteger expiredCallbacks = new AtomicInteger();
        final AtomicBoolean bound = new AtomicBoolean();
        final AtomicReference<KnowledgeOnnxSmoke.Report> recovered = new AtomicReference<>();
        final CountDownLatch retryDone = new CountDownLatch(1);
        try {
            instrumentation.runOnMainSync(() -> bound.set(client.start(
                    new KnowledgeOnnxIsolatedClient.Callback() {
                @Override public void success(final KnowledgeOnnxSmoke.Report ignored) {
                    expiredCallbacks.incrementAndGet();
                    expired.countDown();
                }
                @Override public void failure() {
                    expiredCallbacks.incrementAndGet();
                    expired.countDown();
                }
            })));
            assertTrue("Synthetic delayed bind not accepted", bound.get());
            assertTrue("Synthetic bind deadline never fired", expired.await(5, TimeUnit.SECONDS));
            assertTrue("Timed-out bind was not released", delayed.firstUnbound);
            assertEquals("Synthetic timeout notified more than once", 1, expiredCallbacks.get());

            instrumentation.runOnMainSync(() -> {
                client.restoreDefaultTimeoutForDebugTest();
                bound.set(client.start(new KnowledgeOnnxIsolatedClient.Callback() {
                    @Override public void success(final KnowledgeOnnxSmoke.Report report) {
                        recovered.set(report);
                        retryDone.countDown();
                    }
                    @Override public void failure() { retryDone.countDown(); }
                }));
                // Simulate a stale notification from the previous dead connection.
                delayed.firstConnection.onServiceDisconnected(
                        new ComponentName(context, KnowledgeOnnxIsolatedService.class));
            });
            assertTrue("Synthetic reconnect failed to bind", bound.get());
            assertTrue("Synthetic reconnect never completed", retryDone.await(40, TimeUnit.SECONDS));
            assertNotNull("Synthetic reconnect failed after expired bind", recovered.get());
            assertEquals("Expired synthetic callback ran again", 1, expiredCallbacks.get());
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void fdBindTimeoutWipesBytesAndFreshRequestCanReconnect()
            throws Exception {
        final DelayedBindContext delayed = new DelayedBindContext(context);
        final KnowledgeOnnxIsolatedSessionClient client =
                new KnowledgeOnnxIsolatedSessionClient(delayed, 350L);
        final byte[] discarded = KnowledgeOnnxSmoke.identityModel();
        final String sha = KnowledgeOnnxSessionPipe.sha256Hex(discarded);
        final CountDownLatch expired = new CountDownLatch(1);
        final AtomicInteger expiredCallbacks = new AtomicInteger();
        final AtomicBoolean bound = new AtomicBoolean();
        final AtomicReference<KnowledgeOnnxSessionGate.Report> recovered = new AtomicReference<>();
        final CountDownLatch retryDone = new CountDownLatch(1);
        try {
            instrumentation.runOnMainSync(() -> bound.set(client.start(discarded, sha,
                    new KnowledgeOnnxIsolatedSessionClient.Callback() {
                @Override public void success(final KnowledgeOnnxSessionGate.Report ignored) {
                    expiredCallbacks.incrementAndGet();
                    expired.countDown();
                }
                @Override public void failure() {
                    expiredCallbacks.incrementAndGet();
                    expired.countDown();
                }
            })));
            assertTrue("FD delayed bind not accepted", bound.get());
            assertTrue("FD bind deadline never fired", expired.await(5, TimeUnit.SECONDS));
            assertTrue("Timed-out FD bind was not released", delayed.firstUnbound);
            for (final byte wiped : discarded) assertEquals("Timed-out weights not wiped", 0, wiped);
            assertEquals("FD timeout notified more than once", 1, expiredCallbacks.get());

            final byte[] retry = KnowledgeOnnxSmoke.identityModel();
            final String retrySha = KnowledgeOnnxSessionPipe.sha256Hex(retry);
            instrumentation.runOnMainSync(() -> {
                client.restoreDefaultTimeoutForDebugTest();
                bound.set(client.start(retry, retrySha,
                        new KnowledgeOnnxIsolatedSessionClient.Callback() {
                    @Override public void success(final KnowledgeOnnxSessionGate.Report report) {
                        recovered.set(report);
                        retryDone.countDown();
                    }
                    @Override public void failure() { retryDone.countDown(); }
                }));
                delayed.firstConnection.onServiceDisconnected(
                        new ComponentName(context, KnowledgeOnnxIsolatedService.class));
            });
            assertTrue("FD reconnect failed to bind", bound.get());
            assertTrue("FD reconnect never completed", retryDone.await(40, TimeUnit.SECONDS));
            assertNotNull("FD reconnect failed after expired bind", recovered.get());
            assertEquals("Expired FD callback ran again", 1, expiredCallbacks.get());
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void debugStallWatchdogKillsOnlyWorker() throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        try {
            connection.startStall();
            assertTrue("Debug-only stall did not begin", connection.started.await(5, TimeUnit.SECONDS));
            assertTrue("Worker Binder survived its debug watchdog",
                    connection.died.await(15, TimeUnit.SECONDS));
            assertFalse("Watchdog killed the DPKS app process", KnowledgeOnnxProcessIdentity.isWorker());
        } finally {
            unbindWorker(connection);
        }
    }

    @Test public void cancellingDebugStallKillsWorkerBeforeWatchdog() throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        try {
            connection.startStall();
            assertTrue("Debug-only stall did not begin", connection.started.await(5, TimeUnit.SECONDS));
            connection.messenger.send(Message.obtain(null,
                    KnowledgeOnnxIsolatedProtocol.CANCEL_AND_EXIT));
            // Shorter than the six-second synthetic watchdog: proves explicit cancel.
            assertTrue("Cancel did not kill isolated Binder before watchdog",
                    connection.died.await(5, TimeUnit.SECONDS));
            assertFalse("Cancel killed the DPKS app process", KnowledgeOnnxProcessIdentity.isWorker());
        } finally {
            unbindWorker(connection);
        }
    }

    @Test public void simulatedWorkerOomKillsBinderAndFreshManualRunSucceeds()
            throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        try {
            connection.simulateOom();
            assertTrue("Simulated OOM did not kill isolated Binder promptly",
                    connection.died.await(5, TimeUnit.SECONDS));
            assertEquals("Fatal OOM unexpectedly sent an ONNX result", 1L,
                    connection.responded.getCount());
            assertFalse("Fatal OOM killed the owning process",
                    KnowledgeOnnxProcessIdentity.isWorker());
        } finally {
            unbindWorker(connection);
        }

        // A new explicit start must bind a new worker. The failure itself has
        // no retry and never uses installed models, weights, or user notes.
        Thread.sleep(1000);
        final KnowledgeOnnxIsolatedClient client = new KnowledgeOnnxIsolatedClient(context);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<KnowledgeOnnxSmoke.Report> recovered = new AtomicReference<>();
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicBoolean bound = new AtomicBoolean();
        try {
            instrumentation.runOnMainSync(() -> bound.set(client.start(
                    new KnowledgeOnnxIsolatedClient.Callback() {
                @Override public void success(final KnowledgeOnnxSmoke.Report report) {
                    recovered.set(report);
                    callbacks.incrementAndGet();
                    finished.countDown();
                }
                @Override public void failure() {
                    callbacks.incrementAndGet();
                    finished.countDown();
                }
            })));
            assertTrue("Manual start after OOM failed to bind", bound.get());
            assertTrue("New worker did not respond", finished.await(40, TimeUnit.SECONDS));
            assertNotNull("Fresh generated Identity failed after simulated OOM", recovered.get());
            assertEquals("Fresh manual run invoked multiple callbacks", 1, callbacks.get());
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void fdSessionRejectsWrongShaBeforeNativeOpen() throws Exception {
        final byte[] graph = KnowledgeOnnxSmoke.identityModel();
        final String originalSha = KnowledgeOnnxSessionPipe.sha256Hex(graph);
        graph[graph.length / 2] ^= 1;
        assertRejectedSession(graph, graph.length, originalSha);
    }

    @Test public void verifiedFdIsRefusedByDebugWorkerLowRamGate() throws Exception {
        final byte[] graph = KnowledgeOnnxSmoke.identityModel();
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendSession(pipe[0], graph.length,
                    KnowledgeOnnxSessionPipe.sha256Hex(graph), true);
            closePipe(pipe[0]);
            try (OutputStream sender = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                sender.write(graph);
            }
            assertTrue("Low-RAM worker did not return a final denial",
                    connection.responded.await(15, TimeUnit.SECONDS));
            assertEquals("Forced low RAM must refuse before native createSession",
                    KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_MEMORY_DENIED,
                    connection.responseCode.get());
            assertFalse("Worker memory denial killed the owning app",
                    KnowledgeOnnxProcessIdentity.isWorker());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    /** Independent expected metadata for the generated six-token E5 smoke. */
    private static String expectedE5DescriptorSha256(final boolean tokenTypes,
                                                     final boolean pooler) {
        final Map<String, KnowledgeE5OnnxSignature.Tensor> inputs = new LinkedHashMap<>();
        final Map<String, KnowledgeE5OnnxSignature.Tensor> outputs = new LinkedHashMap<>();
        inputs.put("input_ids", new KnowledgeE5OnnxSignature.Tensor(
                KnowledgeE5OnnxSignature.DataType.INT64, 1, 6));
        inputs.put("attention_mask", new KnowledgeE5OnnxSignature.Tensor(
                KnowledgeE5OnnxSignature.DataType.INT64, 1, 6));
        if (tokenTypes) inputs.put("token_type_ids", new KnowledgeE5OnnxSignature.Tensor(
                KnowledgeE5OnnxSignature.DataType.INT64, 1, 6));
        outputs.put("last_hidden_state", new KnowledgeE5OnnxSignature.Tensor(
                KnowledgeE5OnnxSignature.DataType.FLOAT32, 1, 6, 384));
        if (pooler) outputs.put("pooler_output", new KnowledgeE5OnnxSignature.Tensor(
                KnowledgeE5OnnxSignature.DataType.FLOAT32, 1, 384));
        return KnowledgeE5OnnxSignature.descriptorSha256(inputs, outputs);
    }

    private static void assertWorkerPssSnapshots(final KnowledgeOnnxMemoryTrace memory) {
        assertNotNull("Missing isolated worker PSS report", memory);
        assertTrue("Malformed worker PSS report", KnowledgeOnnxMemoryTrace.valid(
                memory.beforeKiB, memory.maxObservedKiB, memory.afterCloseKiB));
        assertTrue("Worker PSS baseline was unavailable", memory.beforeKiB > 0);
        assertTrue("Open-session worker PSS was unavailable", memory.maxObservedKiB > 0);
        assertTrue("Worker PSS after close was unavailable", memory.afterCloseKiB > 0);
    }

    @Test public void fdSessionRejectsTruncatedAndTrailingBytes() throws Exception {
        final byte[] graph = KnowledgeOnnxSmoke.identityModel();
        final String sha = KnowledgeOnnxSessionPipe.sha256Hex(graph);
        assertRejectedSession(Arrays.copyOf(graph, graph.length - 1), graph.length, sha);
        assertRejectedSession(Arrays.copyOf(graph, graph.length + 1), graph.length, sha);
    }

    @Test public void cancellingBlockedFdReadKillsOnlyWorker() throws Exception {
        final byte[] graph = KnowledgeOnnxSmoke.identityModel();
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendSession(pipe[0], graph.length, KnowledgeOnnxSessionPipe.sha256Hex(graph));
            closePipe(pipe[0]);
            // The receiver knows the complete length, but this writer stays open
            // with only a prefix. The worker must wait for the rest of the pipe.
            try (OutputStream sender = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                sender.write(graph, 0, 16);
                sender.flush();
                assertFalse("Partial FD unexpectedly produced a final session reply",
                        connection.responded.await(800, TimeUnit.MILLISECONDS));
                connection.messenger.send(Message.obtain(null,
                        KnowledgeOnnxIsolatedProtocol.CANCEL_AND_EXIT));
                assertTrue("Cancel failed to kill blocked FD worker",
                        connection.died.await(5, TimeUnit.SECONDS));
            }
            assertFalse("Cancel killed the DPKS app process", KnowledgeOnnxProcessIdentity.isWorker());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void debugLargeFdFixtureIsVerifiedInsideIsolatedWorkerWithoutOrt()
            throws Exception {
        assertTrue(KnowledgeE5LargeFdFixture.LENGTH_BYTES
                > KnowledgeOnnxSessionGate.MAX_MODEL_BYTES);
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendLargeFixture(pipe[0], KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                    KnowledgeE5LargeFdFixture.SHA256);
            closePipe(pipe[0]);
            try (InputStream source = KnowledgeE5LargeFdFixture.open();
                 OutputStream destination = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                KnowledgeE5LargeFdTransfer.copyToQuarantine(source, destination,
                        KnowledgeE5LargeFdFixture.LENGTH_BYTES, KnowledgeE5LargeFdFixture.SHA256);
            }
            assertTrue("Large synthetic FD worker did not respond",
                    connection.responded.await(25, TimeUnit.SECONDS));
            assertEquals("Receiver rejected its independently verified fixture",
                    KnowledgeOnnxIsolatedProtocol.RESULT_DEBUG_LARGE_FD_VERIFIED,
                    connection.responseCode.get());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void debugLargeFdRejectsForgedEnvelopeBeforeReading() throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendLargeFixture(pipe[0], KnowledgeE5LargeFdFixture.LENGTH_BYTES + 1L,
                    KnowledgeE5LargeFdFixture.SHA256);
            closePipe(pipe[0]);
            assertTrue("Unpinned large FD envelope had no reply",
                    connection.responded.await(10, TimeUnit.SECONDS));
            assertEquals(KnowledgeOnnxIsolatedProtocol.RESULT_DEBUG_LARGE_FD_FAILED,
                    connection.responseCode.get());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void debugLargeFdRejectsForgedShaBeforeReading() throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendLargeFixture(pipe[0], KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                    "0000000000000000000000000000000000000000000000000000000000000000");
            closePipe(pipe[0]);
            assertTrue("Unpinned hash had no reply",
                    connection.responded.await(10, TimeUnit.SECONDS));
            assertEquals(KnowledgeOnnxIsolatedProtocol.RESULT_DEBUG_LARGE_FD_FAILED,
                    connection.responseCode.get());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void debugLargeFdRejectsTruncatedPipe() throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendLargeFixture(pipe[0], KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                    KnowledgeE5LargeFdFixture.SHA256);
            closePipe(pipe[0]);
            try (OutputStream destination = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                destination.write(new byte[64]);
            }
            assertTrue("Truncated large FD did not produce a reply",
                    connection.responded.await(15, TimeUnit.SECONDS));
            assertEquals(KnowledgeOnnxIsolatedProtocol.RESULT_DEBUG_LARGE_FD_FAILED,
                    connection.responseCode.get());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void debugLargeFdRejectsExtraBytes() throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendLargeFixture(pipe[0], KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                    KnowledgeE5LargeFdFixture.SHA256);
            closePipe(pipe[0]);
            try (InputStream source = KnowledgeE5LargeFdFixture.open();
                 OutputStream destination = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                final byte[] block = new byte[KnowledgeE5LargeFdTransfer.CHUNK_BYTES];
                int count;
                while ((count = source.read(block)) != -1) destination.write(block, 0, count);
                destination.write(0); // Sender lies about exact EOF.
            }
            assertTrue("Extended large FD did not produce a reply",
                    connection.responded.await(25, TimeUnit.SECONDS));
            assertEquals(KnowledgeOnnxIsolatedProtocol.RESULT_DEBUG_LARGE_FD_FAILED,
                    connection.responseCode.get());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void debugLargeFdRejectsChangedBytesDespitePinnedEnvelope() throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendLargeFixture(pipe[0], KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                    KnowledgeE5LargeFdFixture.SHA256);
            closePipe(pipe[0]);
            try (InputStream source = KnowledgeE5LargeFdFixture.open();
                 OutputStream destination = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                final byte[] block = new byte[KnowledgeE5LargeFdTransfer.CHUNK_BYTES];
                int count;
                boolean changed = false;
                while ((count = source.read(block)) != -1) {
                    if (!changed) {
                        block[0] ^= 1;
                        changed = true;
                    }
                    destination.write(block, 0, count);
                }
                assertTrue(changed);
            }
            assertTrue("Modified large FD did not produce a reply",
                    connection.responded.await(25, TimeUnit.SECONDS));
            assertEquals(KnowledgeOnnxIsolatedProtocol.RESULT_DEBUG_LARGE_FD_FAILED,
                    connection.responseCode.get());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void debugLargeFdCancelKillsBlockedWorkerOnly() throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendLargeFixture(pipe[0], KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                    KnowledgeE5LargeFdFixture.SHA256);
            closePipe(pipe[0]);
            try (OutputStream destination = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                destination.write(new byte[64]);
                destination.flush();
                assertFalse("Partial large FD unexpectedly passed",
                        connection.responded.await(800, TimeUnit.MILLISECONDS));
                connection.messenger.send(Message.obtain(null,
                        KnowledgeOnnxIsolatedProtocol.CANCEL_AND_EXIT));
                assertTrue("Cancel did not kill blocked large FD worker",
                        connection.died.await(5, TimeUnit.SECONDS));
            }
            assertFalse("Cancel killed owning application", KnowledgeOnnxProcessIdentity.isWorker());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void verifyOnlyLargeFdUsesIndependentReceiverDigestWithoutOrt()
            throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendVerifyOnly(pipe[0], KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                    KnowledgeE5LargeFdFixture.SHA256);
            closePipe(pipe[0]);
            try (InputStream source = KnowledgeE5LargeFdFixture.open();
                 OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                KnowledgeE5LargeFdTransfer.copyToQuarantine(source, output,
                        KnowledgeE5LargeFdFixture.LENGTH_BYTES, KnowledgeE5LargeFdFixture.SHA256);
            }
            assertTrue("Verify-only FD worker did not return a result",
                    connection.responded.await(30, TimeUnit.SECONDS));
            assertEquals("Verify-only large FD failed its own exact SHA/EOF",
                    KnowledgeOnnxIsolatedProtocol.RESULT_VERIFY_ONLY_MATCH,
                    connection.responseCode.get());
            assertFalse("Owning app turned into worker", KnowledgeOnnxProcessIdentity.isWorker());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void verifyOnlyRejectsTruncatedPayload() throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            final byte[] bytes = new byte[256];
            final String pinnedSha = KnowledgeOnnxSessionPipe.sha256Hex(bytes);
            connection.sendVerifyOnly(pipe[0], bytes.length, pinnedSha);
            closePipe(pipe[0]);
            try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                output.write(bytes, 0, bytes.length - 1);
            }
            assertTrue("Truncated verify-only FD did not return",
                    connection.responded.await(15, TimeUnit.SECONDS));
            assertEquals(KnowledgeOnnxIsolatedProtocol.RESULT_VERIFY_ONLY_MISMATCH,
                    connection.responseCode.get());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void verifyOnlyRejectsChangedBytesWithPlausibleEnvelope() throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            final byte[] original = new byte[256];
            final String pinned = KnowledgeOnnxSessionPipe.sha256Hex(original);
            original[0] = 1;
            connection.sendVerifyOnly(pipe[0], original.length, pinned);
            closePipe(pipe[0]);
            try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                output.write(original);
            }
            assertTrue("Changed verify-only FD did not return",
                    connection.responded.await(15, TimeUnit.SECONDS));
            assertEquals(KnowledgeOnnxIsolatedProtocol.RESULT_VERIFY_ONLY_MISMATCH,
                    connection.responseCode.get());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void verifyOnlyRejectsOutOfRangeEnvelopeWithoutReading() throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendVerifyOnly(pipe[0],
                    KnowledgeE5LargeFdTransfer.MAX_TRANSFER_BYTES + 1,
                    KnowledgeE5LargeFdFixture.SHA256);
            closePipe(pipe[0]);
            assertTrue("Oversized verify-only envelope did not return",
                    connection.responded.await(10, TimeUnit.SECONDS));
            assertEquals(KnowledgeOnnxIsolatedProtocol.RESULT_VERIFY_ONLY_MISMATCH,
                    connection.responseCode.get());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void verifyOnlyCancelKillsBlockedReaderWithoutHarmingOwner() throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendVerifyOnly(pipe[0], KnowledgeE5LargeFdFixture.LENGTH_BYTES,
                    KnowledgeE5LargeFdFixture.SHA256);
            closePipe(pipe[0]);
            try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                output.write(0);
                output.flush();
                assertFalse("Partial verify-only FD unexpectedly succeeded",
                        connection.responded.await(800, TimeUnit.MILLISECONDS));
                connection.messenger.send(Message.obtain(null,
                        KnowledgeOnnxIsolatedProtocol.CANCEL_AND_EXIT));
                assertTrue("Verify-only cancellation did not kill blocked worker",
                        connection.died.await(5, TimeUnit.SECONDS));
            }
            assertFalse(KnowledgeOnnxProcessIdentity.isWorker());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    @Test public void verifyOnlyClientRequiresBothSenderAndWorkerSuccess()
            throws Exception {
        final KnowledgeE5VerifyOnlyFdClient client = new KnowledgeE5VerifyOnlyFdClient(context);
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicInteger passed = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicBoolean started = new AtomicBoolean();
        try {
            instrumentation.runOnMainSync(() -> started.set(client.startDebugSyntheticFixture(
                    new KnowledgeE5VerifyOnlyFdClient.Callback() {
                @Override public void match() {
                    passed.incrementAndGet();
                    completed.countDown();
                }
                @Override public void failure() {
                    failed.incrementAndGet();
                    completed.countDown();
                }
            })));
            assertTrue("Verify-only client failed to bind", started.get());
            assertTrue("Verify-only client did not finish its FD round trip",
                    completed.await(45, TimeUnit.SECONDS));
            assertEquals("Independent sender and receiver must both verify", 1, passed.get());
            assertEquals("Unexpected verify-only failure callback", 0, failed.get());
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void verifyOnlyClientTimeoutFailsOnceAndCanReconnect() throws Exception {
        final DelayedBindContext delayed = new DelayedBindContext(context);
        final KnowledgeE5VerifyOnlyFdClient client = new KnowledgeE5VerifyOnlyFdClient(
                delayed, 300L);
        final CountDownLatch firstDone = new CountDownLatch(1);
        final AtomicInteger failures = new AtomicInteger();
        final AtomicBoolean firstStarted = new AtomicBoolean();
        try {
            instrumentation.runOnMainSync(() -> firstStarted.set(client.startDebugSyntheticFixture(
                    new KnowledgeE5VerifyOnlyFdClient.Callback() {
                @Override public void match() { firstDone.countDown(); }
                @Override public void failure() {
                    failures.incrementAndGet();
                    firstDone.countDown();
                }
            })));
            assertTrue("Delayed debug bind did not start", firstStarted.get());
            assertTrue("Verify-only bind deadline did not fail",
                    firstDone.await(5, TimeUnit.SECONDS));
            assertEquals("Verify-only callback ran more than once", 1, failures.get());
            assertTrue("Timed-out bind was not unbound", delayed.firstUnbound);

            instrumentation.runOnMainSync(client::restoreDefaultTimeoutForDebugTest);
            final CountDownLatch secondDone = new CountDownLatch(1);
            final AtomicInteger matches = new AtomicInteger();
            final AtomicBoolean secondStarted = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> secondStarted.set(client.startDebugSyntheticFixture(
                    new KnowledgeE5VerifyOnlyFdClient.Callback() {
                @Override public void match() {
                    matches.incrementAndGet();
                    secondDone.countDown();
                }
                @Override public void failure() { secondDone.countDown(); }
            })));
            assertTrue("Verify-only retry refused after timeout", secondStarted.get());
            assertTrue("Verify-only retry did not respond", secondDone.await(45, TimeUnit.SECONDS));
            assertEquals("Fresh FD request failed after timeout", 1, matches.get());
            assertEquals("Timed-out request invoked callbacks after retry", 1, failures.get());
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void verifyOnlyClientRejectsReceiverMatchIfSenderPostflightFailed()
            throws Exception {
        final KnowledgeE5VerifyOnlyFdClient client = new KnowledgeE5VerifyOnlyFdClient(context);
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicInteger matched = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicBoolean started = new AtomicBoolean();
        try {
            instrumentation.runOnMainSync(() -> started.set(client.startDebugPostflightFailure(
                    new KnowledgeE5VerifyOnlyFdClient.Callback() {
                @Override public void match() {
                    matched.incrementAndGet();
                    completed.countDown();
                }
                @Override public void failure() {
                    failed.incrementAndGet();
                    completed.countDown();
                }
            })));
            assertTrue("Postflight rejection test failed to bind", started.get());
            assertTrue("Postflight rejection left client hanging",
                    completed.await(45, TimeUnit.SECONDS));
            assertEquals("Worker match alone must NEVER authorize source", 0, matched.get());
            assertEquals("Postflight failure must be reported once", 1, failed.get());
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    @Test public void verifyOnlyClientCancellationIgnoresLateBindAndNeverReplies()
            throws Exception {
        final DelayedBindContext delayed = new DelayedBindContext(context);
        final KnowledgeE5VerifyOnlyFdClient client = new KnowledgeE5VerifyOnlyFdClient(delayed);
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicBoolean started = new AtomicBoolean();
        try {
            instrumentation.runOnMainSync(() -> {
                started.set(client.startDebugSyntheticFixture(new KnowledgeE5VerifyOnlyFdClient.Callback() {
                    @Override public void match() { callbacks.incrementAndGet(); }
                    @Override public void failure() { callbacks.incrementAndGet(); }
                }));
                assertFalse("Concurrent verify-only request was accepted",
                        client.startDebugSyntheticFixture(new KnowledgeE5VerifyOnlyFdClient.Callback() {
                    @Override public void match() { callbacks.incrementAndGet(); }
                    @Override public void failure() { callbacks.incrementAndGet(); }
                }));
                client.cancel();
            });
            assertTrue(started.get());
            assertTrue("Cancelled verify-only bind was not unbound", delayed.firstUnbound);
            Thread.sleep(350);
            assertEquals("Cancelled verify-only request invoked a callback", 0, callbacks.get());
        } finally {
            instrumentation.runOnMainSync(client::cancel);
        }
    }

    private void assertRejectedSession(final byte[] transmitted, final int advertisedLength,
                                       final String expectedSha) throws Exception {
        final IsolatedWorkerConnection connection = bindWorker();
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            connection.sendSession(pipe[0], advertisedLength, expectedSha);
            closePipe(pipe[0]);
            try (OutputStream sender = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                sender.write(transmitted);
            }
            assertTrue("Invalid FD did not produce a response",
                    connection.responded.await(15, TimeUnit.SECONDS));
            assertEquals("Invalid FD reached a successful native session",
                    KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_FAILED, connection.responseCode.get());
        } finally {
            closePipe(pipe[0]);
            closePipe(pipe[1]);
            unbindWorker(connection);
        }
    }

    private IsolatedWorkerConnection bindWorker() throws Exception {
        final IsolatedWorkerConnection connection = new IsolatedWorkerConnection();
        final AtomicBoolean bound = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> bound.set(context.bindService(
                new Intent(context, KnowledgeOnnxIsolatedService.class), connection,
                Context.BIND_AUTO_CREATE)));
        assertTrue("Isolated worker bind failed", bound.get());
        if (!connection.connected.await(10, TimeUnit.SECONDS)) {
            unbindWorker(connection);
            fail("Isolated worker service never connected");
        }
        return connection;
    }

    private void unbindWorker(final IsolatedWorkerConnection connection) {
        instrumentation.runOnMainSync(() -> context.unbindService(connection));
    }

    private static void closePipe(final ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException ignored) { }
    }

    /** First bind intentionally never connects; later binds use the real service. */
    private static final class DelayedBindContext extends ContextWrapper {
        ServiceConnection firstConnection;
        volatile boolean firstUnbound;

        DelayedBindContext(final Context base) { super(base); }

        @Override public boolean bindService(final Intent intent,
                                             final ServiceConnection connection,
                                             final int flags) {
            if (firstConnection == null) {
                firstConnection = connection;
                return true;
            }
            return super.bindService(intent, connection, flags);
        }

        @Override public void unbindService(final ServiceConnection connection) {
            if (connection == firstConnection) {
                firstUnbound = true;
                return;
            }
            super.unbindService(connection);
        }
    }

    private static final class IsolatedWorkerConnection implements ServiceConnection {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch died = new CountDownLatch(1);
        final CountDownLatch responded = new CountDownLatch(1);
        final AtomicInteger responseCode = new AtomicInteger();
        final Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what == KnowledgeOnnxIsolatedProtocol.DEBUG_STALL_STARTED) {
                started.countDown();
            } else if (message.what == KnowledgeOnnxIsolatedProtocol.RESULT_PASSED
                    || message.what == KnowledgeOnnxIsolatedProtocol.RESULT_FAILED
                    || message.what == KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_PASSED
                    || message.what == KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_FAILED
                    || message.what == KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_MEMORY_DENIED
                    || message.what == KnowledgeOnnxIsolatedProtocol.RESULT_DEBUG_LARGE_FD_VERIFIED
                    || message.what == KnowledgeOnnxIsolatedProtocol.RESULT_DEBUG_LARGE_FD_FAILED
                    || message.what == KnowledgeOnnxIsolatedProtocol.RESULT_VERIFY_ONLY_MATCH
                    || message.what == KnowledgeOnnxIsolatedProtocol.RESULT_VERIFY_ONLY_MISMATCH) {
                responseCode.set(message.what);
                responded.countDown();
            }
            return true;
        }));
        Messenger messenger;

        @Override public void onServiceConnected(final ComponentName name, final IBinder binder) {
            try {
                binder.linkToDeath(died::countDown, 0);
            } catch (RemoteException alreadyDead) {
                died.countDown();
            }
            messenger = new Messenger(binder);
            connected.countDown();
        }

        @Override public void onServiceDisconnected(final ComponentName name) {
            died.countDown();
        }

        void startStall() throws RemoteException {
            final Message request = Message.obtain(null, KnowledgeOnnxIsolatedProtocol.DEBUG_STALL);
            request.replyTo = reply;
            messenger.send(request);
        }

        void simulateOom() throws RemoteException {
            final Message request = Message.obtain(null,
                    KnowledgeOnnxIsolatedProtocol.DEBUG_SIMULATE_OOM);
            request.replyTo = reply;
            messenger.send(request);
        }

        void sendSession(final ParcelFileDescriptor readPipe, final int length,
                         final String expectedSha) throws RemoteException {
            sendSession(readPipe, length, expectedSha, false);
        }

        void sendSession(final ParcelFileDescriptor readPipe, final int length,
                         final String expectedSha, final boolean debugLowMemory)
                throws RemoteException {
            final Message request = Message.obtain(null,
                    KnowledgeOnnxIsolatedProtocol.RUN_MODEL_SESSION);
            request.arg1 = 1;
            request.arg2 = length;
            request.replyTo = reply;
            final Bundle data = new Bundle();
            data.putParcelable(KnowledgeOnnxIsolatedProtocol.PIPE_FD, readPipe);
            data.putString(KnowledgeOnnxIsolatedProtocol.MODEL_SHA256, expectedSha);
            if (debugLowMemory) data.putBoolean(
                    KnowledgeOnnxIsolatedProtocol.DEBUG_FORCE_LOW_MEMORY, true);
            request.setData(data);
            messenger.send(request);
        }

        void sendDebugE5(final ParcelFileDescriptor readPipe, final int length,
                         final String expectedSha, final int variant) throws RemoteException {
            final Message request = Message.obtain(null,
                    KnowledgeOnnxIsolatedProtocol.RUN_DEBUG_E5_FD);
            request.arg1 = 1;
            request.arg2 = length;
            request.replyTo = reply;
            final Bundle data = new Bundle();
            data.putParcelable(KnowledgeOnnxIsolatedProtocol.PIPE_FD, readPipe);
            data.putString(KnowledgeOnnxIsolatedProtocol.MODEL_SHA256, expectedSha);
            data.putInt(KnowledgeOnnxIsolatedProtocol.DEBUG_E5_VARIANT, variant);
            request.setData(data);
            messenger.send(request);
        }

        void sendLargeFixture(final ParcelFileDescriptor readPipe, final long length,
                              final String expectedSha) throws RemoteException {
            final Message request = Message.obtain(null,
                    KnowledgeOnnxIsolatedProtocol.DEBUG_VERIFY_LARGE_FD);
            request.arg1 = 1;
            request.replyTo = reply;
            final Bundle data = new Bundle();
            data.putParcelable(KnowledgeOnnxIsolatedProtocol.PIPE_FD, readPipe);
            data.putString(KnowledgeOnnxIsolatedProtocol.MODEL_SHA256, expectedSha);
            data.putLong(KnowledgeOnnxIsolatedProtocol.DEBUG_LARGE_FD_LENGTH, length);
            request.setData(data);
            messenger.send(request);
        }

        void sendVerifyOnly(final ParcelFileDescriptor readPipe, final long length,
                            final String expectedSha) throws RemoteException {
            final Message request = Message.obtain(null,
                    KnowledgeOnnxIsolatedProtocol.VERIFY_ONLY_LARGE_FD);
            request.arg1 = 1;
            request.replyTo = reply;
            final Bundle data = new Bundle();
            data.putParcelable(KnowledgeOnnxIsolatedProtocol.PIPE_FD, readPipe);
            data.putString(KnowledgeOnnxIsolatedProtocol.MODEL_SHA256, expectedSha);
            data.putLong(KnowledgeOnnxIsolatedProtocol.VERIFY_ONLY_FD_LENGTH, length);
            request.setData(data);
            messenger.send(request);
        }
    }
}
