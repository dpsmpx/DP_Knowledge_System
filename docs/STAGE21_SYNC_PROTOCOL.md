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

## Proposed next protocol contract

1. Each device generates a random P-256 signing identity using Android
   Keystore (no device hardware ID). Pairing carries the public key and
   requires a human-verifiable fingerprint or QR transfer. A revoked peer
   can no longer contribute events.
2. The encrypted portable snapshot carries a signed commitment to the
   exact journal bytes, a schema version and sender fingerprint. The
   receiver checks the SHA chain, authenticated ciphertext, manifest,
   signature and locally pinned peer key before accepting any branch.
3. Foreign branches are stored append-only in app-private no-backup data
   with their signature and exchange commitment. A duplicate import is
   idempotent. Unknown schema revisions are quarantined, not applied.
4. A signed, monotonic local branch plus causal links to acknowledged
   foreign heads forms a DAG. Compatible operation replay is deterministic.
   Incompatible decisions become **CONTESTED**, not facts.
5. Conflict resolution is a **new local event referencing both heads**,
   after explicit side-by-side review of source/provenance; old events stay
   immutable. Transport outages, replay, rollback, revoked peers and
   concurrent writes must be covered by synthetic acceptance tests.

Security note: a portable backup passphrase protects confidentiality, but
does not by itself prove which device authored a branch. Signed identity and
explicit local trust are separate requirements.

## Not implemented in the first read-only slice

Pairing QR/UI, identity signatures, durable inbox, causal replay, conflict
resolution events, remote polling, event acknowledgements and device QA.
Do not label Stage 21 sync as PASS until all these are integrated and tested.
