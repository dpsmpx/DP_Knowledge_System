# DP Knowledge System — Android integration roadmap

Status: in progress. Each numbered stage is a reviewable PR against the
long-lived integration branch `feature/knowledge-android`. A stage is merged
into that branch only after CI and review. `main` retains the released Markor
snapshot until the integrated application is ready.

## Non-negotiable contracts

- Original Markdown files remain authoritative and locally owned by the user.
- Reading/indexing never renames, moves, deletes, rewrites, or silently
  normalizes a note. Reorganization must be a separate approved dry-run with
  collision checks, logging, and rollback.
- Private notes, imported fragments, local index and journal do not enter Git
  or telemetry. Cloud processing needs explicit informed permission.
- An extracted statement is a **claim**, not an accepted fact. The user
  confirms all authoritative changes through preview/accept/edit/reject.
- `UNKNOWN` is not `FALSE`. Contradictions, provenance, and decisions
  remain visible. A language model never grants factual authority.
- Rebuildable search/semantic indexes must be distinct from source notes and
  from the append-only, user-authorized knowledge journal.
- Project/subject/function/document type are distinct metadata axes.
  Embedding similarity and old folder names are evidence, not ground truth.

## Incremental PR sequence

| Stage | Product increment | Acceptance |
|---|---|---|
| 01 | Native Knowledge Library entry point, read-only local vault discovery, bounded filename search, open note in existing Markor editor | Testable on Android; no vault mutation, path escape, or secrets; CI |
| 02 | Persistent derived Markdown index (paths, frontmatter, links, backlinks, full text), incremental reconciliation, safe cancellation | Accurate rebuild and updates; performance test with synthetic large vault |
| 03 | Formal `ksys` integration with deterministic Python/native bridge; reproduce existing fixtures rather than silently reimplement logic | Android ↔ core contract tests, offline execution, parity against Python golden cases |
| 04 | Human-in-the-loop knowledge intake: CLAIM/FACT/HYPOTHESIS, preview, approval, editable interpretations, journal, provenance | No unapproved fact, journal integrity, source trace and undo-safe decisions |
| 05 | Knowledge search, relationships, contradictions, explanations, hypotheses, and temporal queries | Inspectable evidence trees; `UNKNOWN` and `CONTESTED` correctly distinguished |
| 06 | Conversational assistant with explicit grounding and configurable optional local/remote LLM | Source-cited answers, no fabricated proofs, opt-in network and privacy controls |
| 07 | Reliable large-vault UX: background incremental work, backup/restore, conflicting edits, migrations, diagnostics, Android tests and signed prereleases | Long-running realistic synthetic stress tests, crash-safe recovery and release QA |

The stages are medium-sized deliverables; if a stage proves too broad it is
split into separate reviewable PRs rather than bypassing a review gate.

## Stage 01 scope and limits

The new menu item opens the Knowledge Library for the notebook folder already
configured in Markor. A cancellable background scanner discovers `.md` and
`.markdown` entries. Hidden files/directories and symlink escapes are ignored.
Up to 200,000 notes may be discovered; the first 250 matching relative paths
are displayed, with explicit truncation/unreadable-folder warnings. Clicking
an entry delegates to Markor's existing document editor.

This first stage **does not** index note contents, run the formal knowledge
engine, synchronize external storage, change the Android package ID, or
publish a new signed application release. Those capabilities have explicit
subsequent stages. No actual private vault is a test fixture.

## Integration branch and PR process

1. Maintain `feature/knowledge-android` as the continuous integration line.
2. Branch one numbered milestone from its current verified tip.
3. Run its Python and Android checks and open a PR **targeting the integration branch**.
4. Merge the PR after checks, then branch the next stage from the updated
   integration branch. Do not overlap milestones or silently push feature code
   to `main`.
5. Open a separate final PR from integration to `main` only after release QA.
