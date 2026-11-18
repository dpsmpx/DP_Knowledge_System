# Stage 14 Android endurance validation (manual)

This is a **test plan**, not a report of results from a real device. It uses
only synthetic Markdown notes. Never use a personal Obsidian vault for these
stress tests and never attach private notes, paths or logs to public issues.

## Prerequisites

- Signed `com.dpsmpx.dpks` prerelease from the Stage 14 workflow.
- Redmi Note 13 (Android 15) and, if available, an Android 11 test phone.
- PC with Python 3.11+ and `adb` configured, a USB connection and adequate
  free storage. Keep copies of any existing app data before installation.
- An empty dedicated directory, **outside any personal vault**.

## Build a reproducible fake vault

On the PC, from the repository root:

```bash
mkdir -p /tmp/dp-knowledge-stage14-fixtures
python3 scripts/generate_android_stress_vault.py /tmp/dp-knowledge-stage14-fixtures/vault-20k --notes 20000
```

The generator **refuses an existing destination**. For expanded coverage,
create another fresh directory with `--notes 200000`, after checking storage.
The names and content are artificial. Reference hashes are printed for three
files. Copy only the new fixture to an Android-accessible folder; for example:

```bash
adb push /tmp/dp-knowledge-stage14-fixtures/vault-20k /sdcard/Documents/
```

Select that **synthetic notebook** in Markor before testing. Do not point
the application to your real notes while a generated fixture is active.

## Scenarios and observations

| Scenario | Procedure | Acceptance condition |
|---|---|---|
| Initial pass | Open Knowledge Library and refresh synthetic vault | App remains responsive, count matches 20k, source files unchanged |
| User-controlled check | Press **Check once**, leave the library UI | WorkManager job runs locally; count-only progress notification appears |
| Automatic schedule | Enable six-hour offline checks, plug into charging | Work remains persisted; actual execution can be delayed by Android |
| Disable | Uncheck periodic scans during a run | Future periodic work cancelled; unfinished transaction cannot claim success |
| OS interruption | Swipe app from recents or test an ordinary process death, then reopen | Scheduled work may resume/retry; index health does not claim a partial commit |
| Force-stop distinction | `adb shell am force-stop com.dpsmpx.dpks` | Android suspends jobs until manually opening app again: **not a resume guarantee** |
| Notifications denied | Deny Android notification permission, run a manual check | Local scan still works; no optional change alert or private-text leak |
| Notifications enabled | Enable explicit change alerts, edit one fake Markdown note, check again | Count-only alert only after a complete scan and established same-vault baseline |
| Partial traversal | Test inaccessible directories or the 200k scan limit | No speculative deletion/change notification from an incomplete pass |
| Offline mode | Turn off cellular/Wi-Fi for a manual check | Local FTS refresh succeeds without requests to a model server |
| Index repair | Use the separately confirmed **Rebuild derived index** action | Notes are unchanged; indexes can be rebuilt; embeddings require separate consent |
| Background cost | Measure memory/battery and elapsed time at 20k, then 200k | Record actual values; do not infer performance from CI alone |

Useful package-limited commands:

```bash
adb shell dumpsys meminfo com.dpsmpx.dpks
adb shell dumpsys battery
adb shell pidof com.dpsmpx.dpks
```

To validate source preservation, compare the generator's reference SHA-256
values with the corresponding *synthetic* files on-device, for instance:

```bash
adb shell sha256sum /sdcard/Documents/vault-20k/group-000/synthetic-000000.md
```

Do not upload full `logcat` or `dumpsys` to public GitHub issues without
reviewing them for unrelated sensitive information. Prefer reporting only:
device/Android version, test count, elapsed duration, peak PSS, energy change,
number of retries, incomplete status and PASS/FAIL per scenario.

## Signoff

Record measured results on each device before calling Stage 14 fully
validated or promoting integration to `main`. Passing GitHub Actions proves
compilation and unit-level behavior, **not** actual OEM background scheduling,
notification presentation or large-vault endurance.
