# Stage 21 — offline journal exchange and device pairing

Status: **approved direction, partial engineering implementation**. Owner
confirmed on 2026-11-20:

- Independent local/SAF/Syncthing/Google Drive directory transport, with no
  required proprietary Google API/OAuth.
- Explicit pairing with a QR invitation or one-time code and recognizable
  fingerprint; a matching hash alone does **not** authenticate the sender.
- Divergent decisions remain separate, reviewable causal branches.
  Conflicting decisions are resolved manually; no clock-based last-write-wins.
- Original Markdown files are never overwritten by journal synchronization.

## Safety model

The existing authoritative journal is one linear SHA-based chain. Its event
IDs include previous event hash, sequence, time, actor, operation and data.
Offline forks may have different valid continuations from the same ancestor.
Concatenating those continuations would invalidate hashes and replay order.

Journal comparison in this PR is **read-only**: validate every incoming event,
find the longest common prefix, classify identical/local-prefix/remote-prefix/
diverged/unrelated histories, count possible overlaps and show both heads.
Unknown operation impacts prevent any declaration of safe commutativity.
There is no automatic union or foreign event activation.

## Implemented signing and pairing slice (pending Android CI and device QA)

- Each installation creates a Keystore-held P-256 ECDSA signing identity.
  The private key is neither exported nor copied into snapshots.
- **Manage → Device identity and pairing** displays a public invitation,
  pinned fingerprint and a 12-hex-digit verification code. The receiver pastes
  the invitation and independently reads the code from the sender's screen
  (or an independently authenticated channel). Rotation and activity recreation
  preserve a previously displayed invitation; code matches the invitation,
  not an automatic proof of physical presence.
- Trust is explicit in app-private preferences and can be revoked. Pairing is
  independent of the archive password. Copying an invitation alone is not
  sufficient to establish trust; confirmation on a trusted channel matters.
- New portable and daily backups containing a journal also carry a signed
  `meta/peer.json` commitment to the exact journal bytes, authenticated by
  the ZIP manifest and by the Keystore signature. Archive comparison displays
  whether that peer is currently paired. Unsigned legacy archives remain
  read-only inspectable; they are not eligible for trusted synchronization.
- Neither pairing nor comparing a signed snapshot imports, applies or merges
  any remote decision. The signed snapshot is not yet an event-by-event DAG.

## Proposed remaining protocol contract

1. Foreign branches are stored append-only in app-private no-backup data
   with their signature and exchange commitment. A duplicate import is
   idempotent. Unknown schema revisions are quarantined, not applied.
2. A signed, monotonic local branch plus causal links to acknowledged
   foreign heads forms a DAG. Compatible operation replay is deterministic.
   Incompatible decisions become **CONTESTED**, not facts.
3. Conflict resolution is a **new local event referencing both heads**,
   after explicit side-by-side review of source/provenance; old events stay
   immutable. Transport outages, replay, rollback, revoked peers and
   concurrent writes must be covered by synthetic acceptance tests.

Security note: a portable backup passphrase protects confidentiality, but
does not by itself prove which device authored a branch. Signed identity and
explicit local trust are separate requirements.

## Not implemented yet

QR scanning (manual invitation + separately verified code is available),
causal replay, conflict resolution events, remote polling,
event acknowledgements and real-device QA.
Do not label Stage 21 sync as PASS until all these are integrated and tested.

## Encrypted peer inbox slice (pending CI)

Only after archive ciphertext, ZIP manifest, P-256 journal signature,
locally pinned peer identity, and **both Python journal chains** have been
validated, the user may press **Save authenticated branch**. The app stores
an independently AES-256-GCM-encrypted copy under Android's private no-backup
directory with a nonexportable Keystore key. Repeated imports of the same
signed journal are idempotent; the local inbox is bounded to 100 entries and
8 MiB per signed journal. The user can review a saved branch again or
explicitly remove it. Revoking a peer disables trust for its saved branches.

The inbox is *not* authoritative and never accepts, replays, merges, or
overwrites decisions. It is a holding area for later manual causal review.

## Eighth slice: event-level causal review (pending CI)

A user may page through saved foreign-only events (40 at a time), inspect
operation type and possible target overlap, and explicitly open one event to
view its full local-only detail and provenance. The listing contains no
event data or note text, while the detail view warns that its contents may
be personal. Each request checks the encrypted inbox signature and current
pairing trust. Event listings validate both hash chains; detail views
revalidate the foreign chain and expected remote journal SHA before showing
a specific payload. No action in this review
commits any foreign event; actual manual resolution remains unimplemented.

## Ninth slice: immutable human review receipts (pending CI)

The paired-device inbox's individual event detail now offers **Defer** and
**Reject**. An additional confirmation is required. Before committing, Android
reopens and revalidates the encrypted foreign branch, ECDSA commitment and
current pairing; Python rechecks both journal hash chains, the selected
foreign-only event, both expected heads and the remote SHA-256. The current
user's own linear journal gains a `peer_review` event containing only the
trusted fingerprint, foreign branch/event IDs, causal heads and verdict. No
remote payload or note text is copied to this audit receipt.

`peer_review` has **no active-knowledge effect** in deterministic replay.
Duplicate review of the same signed branch/event/verdict is idempotent, and
stale UI confirmations fail closed. Review receipts are included in the
existing signed journal backup on the next explicit export. This is a causal
acknowledgment/audit trail, **not** application of a foreign decision or
resolution of a conflict. `approve` is intentionally not an allowed verdict.

## Tenth slice: source-verified manual adoption (pending CI)

An individual foreign `accept` event may be **explicitly adopted** only if
the peer is still paired and its signed encrypted inbox passes validation,
the current local source note has exactly the original SHA-256, the precise
fragment and locator match, and the locally computed CNL reading, canonical
node and epistemic role agree. Unsupported operation types, other source
types, missing source files, ambiguous readings, already-rejected records,
stale journal heads and possible conflicts fail closed. The confirmation
dialog displays the source excerpt and warns that adoption is a new local
decision, not replay of the foreign hash chain.

The approved single assertion is written as **one new local `accept` event**
with provenance referencing the signed foreign event and *both* causal
heads. No original Markdown is modified. Repeating the same approval is
idempotent. A prior explicit foreign-event rejection prevents adoption. A
foreign acceptance does **not** cross the trust boundary merely because a
signed file was received or a review receipt exists.

This does not yet resolve conflicting claims, reconstruct remote source
notes, or automatically replay all compatible operations. A manual conflict
resolution receipt with explicit causal references, branch acknowledgements,
and clean-device/concurrency QA remain OPEN.

## Eleventh slice: explicit, non-destructive divergent-claim resolution (pending CI)

When a fully source-verified foreign `accept` conflicts with current local
knowledge, the **Review adoption** flow does not permit acceptance. It offers
two independently confirmed actions: **Mark contested** (record unresolved
disagreement) and **Keep local** (record the owner's current preference).
Both append a `peer_resolution` event to the local hash chain containing both
causal heads, peer and branch commitments, foreign node ID and the bounded
IDs of opposing assertions. The original foreign branch remains in the
Keystore-encrypted inbox. The journal reducer keeps the latest manual verdict
in `peer_resolutions` for deterministic replay, independent of device clocks.

Both choices are **audit-only**. In particular, “contested” means *a
quarantined cross-device disagreement*, **not** an automatically accepted
negative fact or a silent downgrade of a previously accepted local fact.
Neither option activates foreign knowledge, changes local proof results or
edits Markdown. The user can still manually retract/revise a local decision
in the normal knowledge workflow and then explicitly review adoption again;
this is never a last-write-wins merge.

Event reviews, adoptions and conflict resolutions recheck current trust and
both expected heads. Duplicate identical resolution receipts are idempotent.
No assumption is made that the selected device has all the peer's original
files; if the exact Markdown is unavailable, the operation is blocked.

Outstanding: durable branch acknowledgements, a full policy for applying
every other journal operation, user-visible cross-device contest indicators
in general search/query views, parallel-concurrency and physical-device QA.

## Twelfth slice: honest dispute indicators (pending CI)

The deterministic replay projection now counts the latest **contested**
foreign resolutions separately from **keep-local** verdicts. The Android
decision list marks local facts/claims whose underlying assertion was named
in such a dispute; the detailed decision view explains that peer knowledge
was not activated. General knowledge query results display a prominent
warning whenever any unresolved cross-device dispute remains, so a locally
proved answer is not mistaken for a globally reconciled one. Diagnostics
also expose the pending-dispute count. No foreign node text or peer identity
is added to general-query outputs. Turning a dispute into **keep local**
clears the unresolved warning for that event, while retaining its audit.

## Thirteenth slice: durable branch-head acknowledgment (pending CI)

The paired, signed encrypted inbox now offers an **Acknowledge branch**
action independent of reviewing individual events. After an additional user
confirmation and a new signature/trust check, Python revalidates both complete
hash chains and the expected local/remote commitments. A `peer_ack` event
records the exact foreign branch head, current local and shared heads,
fingerprint and foreign SHA. It has **no inference or source-file effect**;
duplicate acknowledgments of the same signed branch are idempotent and a
stale confirmation fails closed. This is a persistent causal receipt in the
owner's local linear journal; it is automatically included when that journal
is next exported inside a new signed encrypted snapshot. No network upload
or foreign-event replay is triggered by acknowledgment.

The offline exchange remains deliberately manual. Transitive peer receipt
validation, upstream replay of all operation types, background transport,
anti-rollback high-water enforcement and real-device QA still require work.
