/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.util.Locale;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/** A bounded, count-only, shareable device report. Never append source text or paths. */
public final class KnowledgeDeviceQaReport {
    private static final int MAX_CHARS = 18_000;
    private final StringBuilder details = new StringBuilder();
    private int passed;
    private int warnings;
    private int failed;
    private int skipped;

    public KnowledgeDeviceQaReport(final String mode, final String packageId,
                                   final String version, final int androidApi,
                                   final String abi) {
        details.append("DPKS DEVICE QA v1\n")
                .append("Started UTC: ").append(utcTimestamp()).append('\n')
                .append("Mode: ").append(mode).append('\n')
                .append("Package: ").append(packageId).append('\n')
                .append("Version: ").append(version).append('\n')
                .append("Android API: ").append(androidApi).append('\n')
                .append("ABI: ").append(abi).append('\n')
                .append("Privacy: synthetic fixtures only; vault counted read-only; ")
                .append("no note text, vault paths, API keys or network requests.\n\n");
    }

    public synchronized void add(final String outcome, final String code,
                                 final long elapsedMillis, final String countOnlyDetails) {
        if ("PASS".equals(outcome)) {
            passed++;
        } else if ("WARN".equals(outcome)) {
            warnings++;
        } else if ("FAIL".equals(outcome)) {
            failed++;
        } else if ("SKIP".equals(outcome)) {
            skipped++;
        } else {
            throw new IllegalArgumentException("Invalid QA outcome");
        }
        final String line = String.format(Locale.ROOT, "[%s] %s (%d ms): %s\n",
                outcome, code, elapsedMillis, countOnlyDetails);
        if (details.length() + line.length() < MAX_CHARS) {
            details.append(line);
        }
    }

    public synchronized int failures() {
        return failed;
    }

    public synchronized String render(final long elapsedMillis) {
        return "SUMMARY: PASS=" + passed + " WARN=" + warnings + " FAIL=" + failed
                + " SKIP=" + skipped + " TOTAL=" + (passed + warnings + failed + skipped)
                + " elapsed=" + elapsedMillis + " ms\n\n" + details
                + "\nA PASS applies only to the named test. Skipped/manual tests and product "
                + "features not implemented are NOT validated. Device interruption, "
                + "daily backup, remote sync, model connectivity and OEM scheduling "
                + "still require separate verification.\n";
    }

    private static String utcTimestamp() {
        final SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
