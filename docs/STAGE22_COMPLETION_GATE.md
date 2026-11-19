# Stage 22 completion gate — DPKS Android UI/UX

This checklist is an engineering gate, **not** a device-acceptance certificate.
No original Markdown, private vault, journal contents or signing credentials
may be used as a public fixture. The editor may reuse proven Markor editing
and rendering internals; the user-facing workspace must belong to DPKS.

| Workstream | Code acceptance | Current state |
|---|---|---|
| Direct launcher / Material 3 / theme / four destinations | DPKS launches directly; tabs survive background jobs | Integrated |
| Decisions and provenance | All eight bounded queues, journal detail, guarded actions and history | Integrated, device QA deferred |
| Context-aware editor | Edit/Preview/Save, exact source spans, review selection, links, dock and navigation | Integrated, device QA deferred |
| Interpretation differences | Separate literal text diff and constrained field-by-field CNL/IR comparison; unknown stays unknown | Implemented in PR #72; broad semantic equivalence not claimed |
| Editor layout | Responsive phone/tablet layout, keyboard-safe dock, clear own toolbar/actions; editing/rendering parity with Markor | Implemented in PR #72; physical keyboard/font QA deferred |
| Library at scale | Bounded pages/search with repeatable ordering and accessible next/back; no 250-row dead end | Implemented in PR #72; synthetic 20k QA, device measurements deferred |
| Processes and history | Independent cancellable operations, current state and bounded redacted recent-event history | Implemented in PR #72; no automatic resume after process death |
| Accessible workflow | Screen reader headings and pane identification, bounded large text, reduced motion and adequate touch targets | Implemented in PR #72; actual TalkBack/font QA deferred |
| Discoverability | Concise local-first onboarding/help with provenance/privacy and no automatic journal changes | Implemented in PR #72 |
| Automated regression | Synthetic contracts plus Android UI-state/component checks for navigation, editing, history and approval guards | Implemented in PR #72: JVM/Python CI and one-button suite; physical touches deferred |
| Release | Green Python/Android CI, privacy check, signed APK, verified digest, one final consolidated device checklist | Final PR #72 Python/Android CI green, privacy diff audited, consolidated checklist ready; merge/sign/digest pending |

The Stage 22 UI code items above were implemented with green Android/Python
CI, while signing and integration are tracked separately. **Physical device QA
remains explicitly open**
until the owner verifies the consolidated APK once on disposable notes. This
definition never silently promotes old QA-52–69 debt to PASS and does not
include Stage 21's full-app backup/synchronization implementation.
