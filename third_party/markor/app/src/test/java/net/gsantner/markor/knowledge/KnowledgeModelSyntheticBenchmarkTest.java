/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** No user model, API server, Android runtime, notebook or journal is accessed. */
public final class KnowledgeModelSyntheticBenchmarkTest {
    @Test public void fourRolesHaveDeterministicSyntheticWorkAndBoundedReports()
            throws Exception {
        final Set<KnowledgeModelPolicy.Role> tested = new HashSet<>();
        for (final KnowledgeModelPolicy.Role role : KnowledgeModelPolicy.Role.values()) {
            final KnowledgeModelSyntheticBenchmark.Report first =
                    KnowledgeModelSyntheticBenchmark.run(role);
            final KnowledgeModelSyntheticBenchmark.Report second =
                    KnowledgeModelSyntheticBenchmark.run(role);
            assertSame(role, first.role);
            assertTrue(tested.add(role));
            assertEquals(KnowledgeModelSyntheticBenchmark.TRIALS, first.trials);
            assertEquals(KnowledgeModelSyntheticBenchmark.units(role), first.unitsPerTrial);
            assertTrue(first.unitsPerTrial > 0);
            assertTrue(first.medianNanos > 0);
            assertTrue(first.p95Nanos >= first.medianNanos);
            assertTrue(first.heapBeforeBytes >= 0);
            assertTrue(first.maxObservedHeapBytes >= first.heapBeforeBytes);
            assertTrue(first.maxObservedHeapBytes >= first.heapAfterBytes);
            assertEquals("A synthetic workload must not depend on wall-clock randomness",
                    first.checksum, second.checksum);
        }
        assertEquals(4, tested.size());
    }

    @Test public void cancellationAndInvalidRoleAreRejectedWithoutWork() throws Exception {
        try {
            KnowledgeModelSyntheticBenchmark.run(null);
            fail("Null role accepted");
        } catch (IOException expected) { }
        Thread.currentThread().interrupt();
        try {
            KnowledgeModelSyntheticBenchmark.run(KnowledgeModelPolicy.Role.EMBEDDING);
            fail("Interrupted benchmark ran anyway");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
