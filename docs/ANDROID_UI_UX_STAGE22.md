# Android UI/UX Stage 22 — first workspace shell slice

Status: **implemented in integration branch, device UI acceptance pending**.
This is *not* the full redesign of the inherited Markor editor.

## Scope

- The DP Knowledge System screen has its own **Material 3 DayNight** theme
  with optional Android 12+ wallpaper-derived Dynamic Color. The legacy Markor
  editor's theme, editor functionality and file handling remain unchanged.
- Fixed bottom navigation separates **Library**, **Processes** and **Manage**.
  Switching sections only changes view visibility, **not** activity/workspace
  identity or background worker lifetime.
- The default **Library** screen shows selected vault identity, scan/index
  state, search, grounded Q&A actions and a bounded recycled note list. The
  long vertical stack of operational buttons no longer pushes search results
  offscreen. An empty-state message and 56dp minimum note-row height are added.
- **Processes** groups semantic analysis, embedding operations, optional
  background work and the two one-button device QA flows. In-progress jobs
  are indicated by a numerical badge on the Processes destination; analysis,
  embedding and device QA reports remain viewable without blocking navigation.
- **Manage** groups accepted decision review, knowledge audit, index
  diagnostics and *journal-only* encrypted export/import. The backup card
  explicitly warns that this is not a full app/vault backup.
- The existing actions and IDs are preserved and covered by static resource
  tests. Cancelling an embedding job no longer cancels an unrelated device QA
  run.

There are **no changes to authority, knowledge journal semantics, Markdown
sources, LLM permissions, APIs or the public data shipped with the APK**.

## One-device manual acceptance checklist (not automated PASS)

On an Android 15 device with test notes (avoid private journal mutations):

1. Open **DP Knowledge System**. Confirm the three bottom destinations,
   readable labels, color contrast in light/dark mode and wallpaper-driven
   color on Android 12+.
2. In **Library**, verify 760-note search (or another disposable collection),
   scroll, open note, long-press actions, search keyboard behavior, no toolbar
   or system-navigation overlap, and blank-query empty state.
3. Move to **Processes**, start the existing synthetic **Run device QA + copy**,
   switch to Library while it runs and back. The process badge must appear,
   the job must continue, status must update, and clipboard must contain the
   redacted report. Never run real provider requests for this check.
4. Repeat the tab switch during synthetic 20k stress only if storage and
   battery allow. Verify cancellation uses its original action and the UI
   remains responsive. Note cold/warm navigation and memory if possible.
5. In **Manage**, verify all review/audit/diagnostics/export/import controls
   are reachable. **Cancel** destructive or restorative dialogs. Do not
   import backup data into a real journal as a UI smoke test.
6. Rotate the device or change system theme, check selected tab restoration,
   text scaling, keyboard dismissal, contrast and readable scrolling on a
   small screen. Check accessibility labels with TalkBack if available.
7. Re-run the original synthetic Stage 15/16/17 reports; previous SQLite
   WARN and unfinished external/device checks must not be recast as PASS.

## Remaining UI/UX work

- A full independent DPKS editor and intelligent workspace replacing the
  remaining Markor navigation and note-editing shell.
- Accessible large-list navigation beyond the current 250 visible results,
  filtering/chips, meaningful progress history and pause/resume controls.
- Inline review/diff viewer with source spans and reversible proposal preview,
  instead of cascading dialogs for Stage 17 decisions.
- Screen-reader audit, reduced motion, large-font layouts, adaptive tablets,
  gesture navigation/edge-to-edge insets, instrumented UI tests and measured
  scrolling/rendering under 20k notes.
- Complete portable app backup, secure secret management and controlled sync.

No UI work changes the owner-confirmed rule: original Markdown is never
rewritten by autonomous analysis, and human confirmation is required for
authoritative knowledge changes outside a separately approved strict policy.
