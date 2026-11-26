/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MODEL-M2: offline comparison of curated weight files against the local shelf
 * and one OS resource snapshot. Published MB sizes are approximations, not
 * verified byte lengths or model-RAM/latency predictions. No model is loaded.
 */
final class KnowledgeModelComparison {
    // Same minimum remaining space that the weight importer enforces while copying.
    static final long STORAGE_RESERVE_BYTES = 16L * 1024L * 1024L;

    enum Storage { INSTALLED, ESTIMATED_FITS, MAY_NOT_FIT, UNKNOWN }
    enum Ram { WEIGHT_ABOVE_SNAPSHOT, WEIGHT_WITHIN_SNAPSHOT, UNKNOWN }

    static final class Row {
        final KnowledgeModelCatalog.Artifact artifact;
        final KnowledgeModelFiles.Entry installedEntry;
        final String title;
        final boolean installed;
        final boolean preferred;
        final long weightBytes;
        final Storage storage;
        final Ram ram;
        final long weightPercentOfAvailableRam;

        private Row(final KnowledgeModelCatalog.Artifact source,
                    final KnowledgeModelFiles.Entry matchingInstalled,
                    final boolean selected, final long freeBytes, final long availableRamBytes) {
            if (source == null && matchingInstalled == null) {
                throw new IllegalArgumentException("Missing model comparison identity");
            }
            artifact = source;
            installedEntry = matchingInstalled;
            title = source != null ? source.title : matchingInstalled.name;
            installed = matchingInstalled != null;
            preferred = installed && selected;
            weightBytes = installed ? matchingInstalled.bytes
                    : source.approximateMegabytes * 1_000_000L;
            if (installed) storage = Storage.INSTALLED;
            else if (freeBytes <= 0) storage = Storage.UNKNOWN;
            else if (weightBytes > freeBytes
                    || freeBytes - weightBytes < STORAGE_RESERVE_BYTES) {
                storage = Storage.MAY_NOT_FIT;
            } else storage = Storage.ESTIMATED_FITS;

            if (availableRamBytes <= 0) {
                ram = Ram.UNKNOWN;
                weightPercentOfAvailableRam = -1;
            } else {
                ram = weightBytes > availableRamBytes
                        ? Ram.WEIGHT_ABOVE_SNAPSHOT : Ram.WEIGHT_WITHIN_SNAPSHOT;
                weightPercentOfAvailableRam = Math.round(
                        100.0 * weightBytes / availableRamBytes);
            }
        }
    }

    private KnowledgeModelComparison() { }

    static List<Row> forRole(final KnowledgeModelPolicy.Role role,
                             final List<KnowledgeModelFiles.Entry> installed,
                             final String preferredSha, final long freeBytes,
                             final long availableRamBytes) {
        if (role == null || installed == null) throw new IllegalArgumentException("Missing model role/shelf");
        final List<Row> result = new ArrayList<>();
        for (final KnowledgeModelCatalog.Artifact artifact : KnowledgeModelCatalog.forRole(role)) {
            KnowledgeModelFiles.Entry matching = null;
            for (final KnowledgeModelFiles.Entry entry : installed) {
                if (entry != null && entry.role == role
                        && artifact.expectedSha256.equals(entry.sha256)) {
                    matching = entry;
                    break;
                }
            }
            result.add(new Row(artifact, matching,
                    artifact.expectedSha256.equals(preferredSha), freeBytes, availableRamBytes));
        }
        // A user's SAF/URL import is just as important as a curated file.
        // Keep each extra installed variant once; the catalog rows already
        // represent matching SHA-256 digests. No package/model bytes are read.
        for (final KnowledgeModelFiles.Entry entry : installed) {
            if (entry == null || entry.role != role) continue;
            boolean known = false;
            for (final Row row : result) {
                if (row.installedEntry != null
                        && entry.sha256.equals(row.installedEntry.sha256)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                result.add(new Row(null, entry,
                        entry.sha256.equals(preferredSha), freeBytes, availableRamBytes));
            }
        }
        return Collections.unmodifiableList(result);
    }
}
