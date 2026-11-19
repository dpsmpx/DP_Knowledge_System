# Stage 22 — DPKS editor intelligence acceptance debt (QA-59)

Status: **OPEN**. This fourth slice has automated *synthetic* contract
checks, not physical-device signoff. It does **not** replace the Markor
editing engine or implement full independent Markdown rendering.

## Expected scope

- Separate DPKS editor toolbar with Edit / Preview, Analyze, Review selection
  and Links. Inherited Markdown edit, preview, formatting and save behavior
  stays in Markor.
- Local source-fragment analysis and Unicode-aware selection mapping use
  the bundled Python ksys parser without transmitting note content.
- A review handoff only uses the *saved* Markdown source and refuses unsaved
  buffers. The library revalidates its source SHA before interpretation;
  the existing core validates provenance and journal head again on commit.
- Incoming links come from an existing **complete** local index scanned
  for the **same vault**. They are lexical possibilities, not verified facts.
  This read-only editor query must not create/migrate/rebuild an index.

## Required manual acceptance on Android 15 (synthetic files only)

1. Launch DPKS directly, open a disposable Markdown note. Confirm the editor
   shows all new controls without covering the keyboard, content or
   Markor's save/format menus on a Redmi Note 13 and with large system text.
2. Alternate **Правка/Просмотр**. Confirm edits, markdown rendering
   (headers, tables, links, code), cursor/selection and save still work.
   Change mode through a legacy Markor action; the DPKS toggle must reflect
   the state. Return to Library and verify the DPKS task survives.
3. Press **Анализ** on a synthetic note. Check fragment locators/counts,
   scrollability, Russian/English labels, explicit interpretation wording
   and that opening/canceling the list does **not** change the journal or note.
4. Select text containing an emoji or other non-BMP character and review.
   Check that the correct complete source fragment is displayed, not an
   offset-shifted neighbor. A cross-fragment selection and an empty selection
   must refuse to guess. Test a large note with more than 24 fragments.
5. Type an **unsaved** edit, then select/review: the editor must request a
   save, never authorize a different saved source or silently write the
   journal. Save manually and repeat with a disposable journal: a normal
   review preview appears; cancel it without commit. Edit/rewrite the
   source before confirmation and verify the freshness guard rejects it.
6. Check **Ссылки** with a complete local index: possible backlink list,
   opening a target note, back navigation. Repeat with no index, incomplete
   scan, a switched vault, ambiguous aliases and a missing target. No
   query may create an index or claim semantic proof.
7. Rotate the screen, switch apps during analysis, tap multiple times and
   use Back. No stale modal, frozen UI, unintended journal write or leaked
   private text in logs/reports should appear.
8. Check offline behavior, dark/light/dynamic themes, TalkBack, font scale
   200%, gesture navigation, battery/process death and 20k-note performance.
   Record device model/Android build, APK hash, timing and redacted results.

## Known omissions, not PASS

- **QA-59 / OPEN:** no complete hardware verification of the fourth slice.
- **EDITOR-58 / PARTIAL:** an explicit editor mode control, selection analysis
  and possible-link browser now exist. Markor still owns actual Markdown
  editing/rendering and file persistence; contextual inline sidebar,
  semantic backlinks and rich source-span comparison are not implemented.
- **QA-54 / PARTIAL USER FEEDBACK:** owner reports working decision review in
  Preview #14. This does not close unperformed TalkBack, stress or lifecycle
  acceptance checks in docs/STAGE22_DECISIONS_QA.md.
- **SQLITE-46, QA-52, QA-53, MERGE-17, BATCH-17 / OPEN:** existing unresolved
  physical-device or read-only SQLite integrity warnings are unaffected.

Use only disposable notes/journals for state-changing validation. Never
include private text, filesystem paths or credentials in public CI/QA logs.
