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
import java.io.OutputStream;
import java.util.Arrays;
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

    @Test public void generatedIdentitySmokeCanRunTwiceAcrossWorkerLifecycle()
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
                assertNotNull("Generated Identity CPU test failed", report.get());
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
            assertTrue(report.get().sessionInitMillis >= 0);
            assertWorkerPssSnapshots(report.get().workerMemory);
            assertTrue("Worker kernel RSS high-water is not bounded",
                    KnowledgeOnnxRssHighWater.valid(report.get().workerRssHighWaterKiB));
        } finally {
            instrumentation.runOnMainSync(client::cancel);
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
                    || message.what == KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_MEMORY_DENIED) {
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
    }
}
