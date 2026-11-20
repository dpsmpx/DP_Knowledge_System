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
