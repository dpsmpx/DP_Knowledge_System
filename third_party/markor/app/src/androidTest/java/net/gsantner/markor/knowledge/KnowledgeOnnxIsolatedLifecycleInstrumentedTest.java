/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

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
    }
}
