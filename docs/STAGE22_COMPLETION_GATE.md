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
| Interpretation differences | Separate literal text diff and constrained field-by-field CNL/IR comparison; unknown stays unknown | Fourteenth slice in review |
| Editor layout | Responsive phone/tablet layout, keyboard-safe dock, clear own toolbar/actions; editing/rendering parity with Markor | Responsive dock in review; remaining chrome/accessibility open |
| Library at scale | Bounded pages/search with repeatable ordering and accessible next/back; no 250-row dead end | Open |
| Accessible workflow | Screen reader labels, focus, large text, reduced motion and adequate touch targets | Open |
| Discoverability | Concise local-first onboarding/help with provenance/privacy and no automatic journal changes | Open |
| Automated regression | Synthetic contracts plus Android UI-state/component checks for navigation, editing and approval guards | Open |
| Release | Green Python/Android CI, privacy check, signed APK, verified digest, one final consolidated device checklist | Open |

Stage 22 may be called *code complete* only after every code acceptance item
is implemented and CI passes. **Physical device QA remains explicitly open**
until the owner verifies the consolidated APK once on disposable notes. This
definition never silently promotes old QA-52–69 debt to PASS and does not
include Stage 21's full-app backup/synchronization implementation.
