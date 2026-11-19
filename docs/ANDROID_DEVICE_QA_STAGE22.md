# Stage 22 — one consolidated Android acceptance (owner-facing)

**Status: OPEN until the signed final APK is actually tried on a phone.**
No interim installation was required for slices 14–20. Check this list once
on disposable, backed-up Markdown notes and a separate throwaway journal.
Never paste actual note text, absolute vault paths, tokens or passwords into
the public issue, PR, release comments or device QA report.

## Preparation

1. Download the final signed `dpks-*-signed.apk` and its `.sha256` from the
   GitHub prerelease. Verify the checksum, `com.dpsmpx.dpks` package name and
   APK signing information. Retain a backup of original notes. An APK update
   is not a substitute for full application/vault backup.
2. Launch the **DP Knowledge System** icon directly. Verify four bottom tabs:
   Library, Decisions, Processes, Settings. The initial explanation should be
   dismissible and can be reopened from Settings. Verify that original text,
   machine interpretation, human assertion, human-confirmed fact and inferred
   link have clearly distinct descriptions.

## One-button test first

3. In Processes, run **Test Stage 22 editor + copy**. Verify `ENV-01`,
   `UI-STATE-22`, `UI-HISTORY-22`, and each `EDITOR-*` synthetic check on the
   APK, with zero unexpected FAIL. The report must still say
   `EDITOR-22-UI: SKIP`; it does **not** automate real taps, rotation, UI
   screenshots, network privacy or the actual editor widget. If the result
   includes `JSONException`, record only the error class and APK version.
4. Run quick synthetic QA and, if time/storage permit, the 20k-note synthetic
   test; verify cancellation and redacted output. Do not rebuild a real SQLite
   index to hide an unexpected read-only `quick_check` warning.

## One combined UI and safety pass

5. Library: search, page Next/Previous beyond 250 matches, change query,
   rotate/reopen, inspect stale/incomplete index labels; verify you can reach
   the last page of a 760/20k synthetic corpus with no duplicate page rows.
6. Decisions: explore all eight categories with an empty and a synthetic
   populated journal. Open a full record, read original source, machine
   interpretation, formalization, provenance, conflicts and history. Back
   returns to the Decisions list, not out of DPKS. Preview accept/reject,
   defer, revise, split, merge, retract and batch (2–8 proposals) using the
   existing fingerprint and journal-head guards. Cancel a preview and verify
   no journal or original Markdown changed.
7. Editor: use edit/preview/save with headings, tables, code, links and emoji;
   inspect context dock at 200% system font on portrait/landscape. Try a real
   keyboard and any available tablet/foldable/multi-window configuration.
   Verify collapsed panel still exposes status and no controls obstruct Save.
8. Select a Unicode/emoji fragment to review. With unsaved edits verify the
   guard refuses confirmation; save explicitly and repeat. Check mismatched
   disk/source SHA, stale journal and multiple-fragment selections refuse
   to guess. Test unsupported punctuation followed by a **separate** human
   wording, without changing the original Markdown automatically.
9. Context: source/KSF separate fields, exact duplicate-span jump, possible
   versus confirmed links, ephemeral highlights, bounded literal diff and
   CNL structural comparison. Missing/ambiguous/old provenance must remain
   unavailable rather than appearing as a proven fact. Follow a cross-note
   link, use Back inside the panel, then Back to the library; check Recents.
10. Processes: start/cancel analysis, audit or synthetic QA. Navigate among
    tabs while workers run; verify no frozen UI. Check bounded recent-event
    history shows categories/time/outcome only and does not pretend a previous
    Start event is currently active. Background-process death and OEM timing
    are not implied by this screen; note whether a task actually resumes.
11. Accessibility/privacy: light/dark/Dynamic Color, 200% font, TalkBack
    headings and reading order, 48dp targets, reduced animations, Back,
    orientation, keyboard/IME, Android Recents, offline default. Ensure no
    original note changes without a deliberate save and no journal append
    without explicit approval. Don't upload screen captures of personal notes.

## Reporting

Mark each scenario PASS/FAIL/SKIP with the APK tag, SHA-256, Android API,
screen size and whether it used **synthetic** content; redact everything else.
Old QA-52–74 and `SQLITE-46`, `MERGE-17`, `BATCH-17` remain OPEN unless the
specific matching scenario was actually observed. One successful test does
not certify other scenarios or an independent editor engine.
