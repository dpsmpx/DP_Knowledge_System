/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

/** No /proc, native model, or process termination is needed for parser tests. */
public final class KnowledgeOnnxRssHighWaterTest {
    @Test public void parsesOnlyBoundedKernelVmHwmInKib() {
        assertEquals(48236L, KnowledgeOnnxRssHighWater.parseLine("VmHWM:\t48236 kB"));
        assertEquals(1024L, KnowledgeOnnxRssHighWater.parseLine("VmHWM: 1024\tkB"));
        assertTrue(KnowledgeOnnxRssHighWater.valid(48236));
        assertTrue(KnowledgeOnnxRssHighWater.valid(KnowledgeOnnxRssHighWater.UNKNOWN));
    }

    @Test public void rejectsWrongFieldUnitMalformedAndHugeValues() {
        for (final String input : new String[]{
                null, "", "VmRSS: 100 kB", "VmHWM: 5 MB", "VmHWM: -1 kB",
                "VmHWM: +1 kB", "VmHWM: 0 kB", "VmHWM: NaN kB",
                "VmHWM: 1099511627777 kB", "VmHWM: 12 kB garbage",
                "VmHWM: 12", "VmHWM: 12.0 kB"}) {
            assertEquals("Invalid kernel high-water value was accepted: " + input,
                    KnowledgeOnnxRssHighWater.UNKNOWN,
                    KnowledgeOnnxRssHighWater.parseLine(input));
        }
        assertFalse(KnowledgeOnnxRssHighWater.valid(0));
        assertFalse(KnowledgeOnnxRssHighWater.valid(-2));
        assertFalse(KnowledgeOnnxRssHighWater.valid(1L << 31));
    }
}
