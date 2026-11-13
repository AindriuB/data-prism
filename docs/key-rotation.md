---
title: Rotating keys
description: Operator procedures for changing each key Data Prism 0.8 uses, and what each change costs, from the audit and link keys to a planned cluster restart.
---

# Rotating keys

Data Prism 0.8 uses five kinds of key. This page gives one procedure for each, says what the change costs, and says
what 0.8 does not do. It describes what 0.8 ships: where a procedure would be easier with a feature that is not
built, the page says so instead of improvising around it. It supports an operator who has to change a key; it does
not judge whether a deployment's key management meets any legal duty.

| Key | Property | Where it is used | Change it by |
|---|---|---|---|
| Pseudonymisation key | `dataprism.privacy.hmac-key` | Derives every synthetic value and the parameter fingerprint in the audit trail | A restart. Every pseudonym changes. See [the pseudonymisation key](#the-pseudonymisation-key) |
| Audit key | `dataprism.audit.hmac-key`, `retired-hmac-keys` | Keys the audit hash chain | A restart, keeping the old key for verification. See [the audit key](#the-audit-key) |
| Cluster key | `dataprism.hazelcast.security.cluster-key` | Seals the cluster's identity and re-identification entries, member cards and sentinel | A planned full restart that loses the cluster's state. See [the cluster key](#the-cluster-key) |
| Name key | `dataprism.hazelcast.security.name-key` | Derives every map key and lock key in the cluster | A planned full restart that loses the cluster's state. See [the name key](#the-name-key) |
| Link keys | `dataprism.links.key-id`, `dataprism.links.keys` | Seals links and page links | Two phases, then retirement; a rolling restart can follow them. See [link keys](#link-keys) |

## Rules for every key

- A key is only ever a reference: an environment variable name or a provider reference. There is no property that
  takes key material, and a literal `value` is refused (`LITERAL_SECRET_FORBIDDEN`). Keep key material out of your
  repository, your configuration files and your logs.
- Every key is at least 32 bytes. Generate each one on its own from a source of randomness. Never derive one key from
  another: the name key is never derived from the pseudonymisation key, and no key is derived from the cluster key.
- Give every key an id that you have not used before, including for a replacement of the same kind. Putting new bytes
  under an old id is not a rotation: other members still know the id as the old key. For the cluster keys and the name key, a member whose key check
  value for an id differs from another member's for the same id makes the cluster stop serving (`KEY_CHECK_MISMATCH`). The
  cluster keys and the name key must also have ids that differ from one another (`DUPLICATE_CLUSTER_KEY_ID`).
- The startup checks refuse a key that shares an id or its bytes with another kind: the cluster and name keys are
  compared with the pseudonymisation, audit and link keys (`CLUSTER_KEY_SHARED`, `NAME_KEY_SHARED`), a link key with
  the pseudonymisation key (`LINK_KEY_SHARED`), and an audit key with the pseudonymisation key
  (`AUDIT_KEY_REUSES_PSEUDONYMISATION_KEY`). The checks cover what they compare; they cannot see a key that was
  derived from another.
- Retiring a key means destroying it in the key store: the secret, the environment variable, the provider entry and
  any backup or deployment file that holds a copy. Removing it from Data Prism's configuration only stops Data Prism
  reading it.
- The packaged server reads keys from environment variables only. A key given as a `provider-reference` is refused
  there with `MISSING_KEY_PROVIDER`. The key's bytes are the UTF-8 bytes of the variable's text. A custom
  `HmacKeyReferenceResolver` can serve provider references; see
  [Migrating from 0.7.x to 0.8.0](migration-0.8.md#breaking-provider-references-reach-resolveproviderreference).
- Each procedure changes configuration and restarts. Test the order on a copy of the deployment first. The property
  tables are in [Configuration](configuration.md).

## The pseudonymisation key

`dataprism.privacy.hmac-key` names the key the engine derives synthetic values with. A pseudonym is a function of the
scope, the subject, the namespace and a `PseudonymisationVersion`: the algorithm, its version (`v1`), the key id and the
vocabulary id, which names the exact word lists in use. Changing any part changes every pseudonym it produces. The
parameter fingerprint in the audit trail is derived under the same key.

What 0.8 does, and does not do, with this key:

- The starter builds one `PseudonymisationVersion` from `hmac-key.key-id` and one key provider that serves only that
  id. A call to a key id other than the configured one fails with `unconfigured HMAC key id`. There is no property to
  run two pseudonymisation keys side by side.
- A scope has no stored record that carries the key id. `scopeId` is derived from the caller's case, and every tool
  call builds its session from the version the process started with. "Pinned" therefore means pinned to what the
  running process was configured with. A scope that is still open when the process restarts continues under the key
  the new process was given.
- `MultiKeySecretKeyProvider` and `EnvironmentSecretKeyProvider` hold several key ids at once. They are plain-Java
  classes for an application that builds its own wiring. The starter's key provider is the one that wins injection, so
  an application bean does not replace it.
- With a cluster, the identity cache is keyed by scope, subject and namespace, not by key id. A cached entry is
  returned as it was stored, so after a key change members can keep returning the old pseudonym for a subject whose
  entry is still cached, and the new one for a subject that is not. Entries end with their scope.

The procedure:

1. Decide that the change is worth it. Every pseudonym for every subject in every scope changes from the first call
   under the new key. Anyone who compares output from before and after the change will see different pseudonyms for
   the same subject, and nothing in the output says a key changed.
2. Record, outside Data Prism, the old key id, where its key is kept, the vocabulary id and the date and time of the
   change. The audit event carries the pseudonym and the parameter fingerprint but not the key id that produced them,
   so this record is the only way to know later which key a given event needs.
3. Finish or let lapse every re-identification request you still need decided. A request left open when the cluster
   restarts is lost, and so is the index entry it needs (see step 6).
4. Generate the new key and give it a new id, and store it where the deployment reads its keys. Do not touch the old
   key.
5. Set `dataprism.privacy.hmac-key.key-id` and its environment variable (or provider reference) to the new key.
6. Restart. For a deployment with `topology: embedded`, stop every member before starting any, and use only
   the stop-all, then start-all order of [the cluster key](#the-cluster-key) (its steps 3 and 5). Do not generate or
   change a cluster key or a name key, and do not destroy either; they stay as they are. A rolling restart is not a safe way to change
   this key: members on the new key and members on the old key, sharing one identity cache, give different answers for
   the same subject. A full stop empties the identity cache, which the new key then refills, and empties the
   re-identification index, so pseudonyms issued before the change can no longer be re-identified. There is no
   approved alternative to the index, and this page does not describe one.
7. Check that new calls return pseudonyms under the new key. A subject that was read before and after the change
   should now show a different pseudonym, and the `dataprism.identity.collision` counter should not rise. The counter
   counts cached identities for a live scope that disagree with what the generator now derives, so a rise means the
   key, the algorithm version or the vocabulary differs from what produced the cache, for example a member still
   running on the old key.
8. Keep the old key, under its old id, in the key store (see the next section).

New scopes need nothing further: every scope that starts after the restart uses the new key from its first call.

### Keeping the old key for tracing an old exposure

The audit trail records the subject pseudonym, the parameter fingerprint, the tool, the source systems and the
policy decision. From those and the old key a past response can in principle be derived again, because a
pseudonym is deterministic. Doing so needs the old key, the same algorithm and version, and the same vocabulary id;
the vocabulary id is content-addressed, so editing a word list changes it. 0.8 ships no command that does this. A
plain-Java application can build a `PseudonymisationVersion` for the old key id with the same vocabulary and ask the
generator.

So keep the old key, under its old id, with your record from step 2, for as long as audit records written under it can
be investigated. The audit retention period (`dataprism.audit.retention`) is the lower bound a deployment has set for
that, not a rule: a longer investigation or a legal hold keeps the records and so keeps the need. Destroying the old
key ends the ability to trace those events by this route.

**An open question.** Whether tracing an old exposure should depend on the key and the `PseudonymisationVersion`
staying stable has not been decided. A key change or a version change could end the ability to trace an old exposure,
and the owner has not confirmed whether that coupling is intended. This page states how 0.8 behaves and does not
answer the question. If your deployment needs to trace exposures across a key change, treat the old key's retention
as a decision to take explicitly, and ask before relying on the current behaviour.

## The audit key

`dataprism.audit.hmac-key` turns the audit chain from plain SHA-256 into HMAC-SHA256 under a key you hold. It is
opt-in. It resists a writer without the key; it does not resist anyone who holds it, the operator and the process that
writes the file included. See [`dataprism.audit`](configuration.md#dataprismaudit).

The recorder signs with the current key only. Each process start begins a new writer chain at `GENESIS` under the key
the process was given, so a key change on restart leaves the earlier writer's chain verifying under the old key and
starts a new chain under the new one. Retired keys never reach the recorder; they are for verification, by the
offline verifier and by retention.

The procedure:

1. Generate the new key and give it an id that has not been used. It must differ from the pseudonymisation, cluster
   and name keys.
2. Move the current key into `dataprism.audit.retired-hmac-keys.<old-key-id>` with its reference, and set
   `dataprism.audit.hmac-key` to the new key id and reference. The old key stays configured; removing it now would
   stop retention (see step 6).
3. Restart. Each instance's next start writes under the new key. A rolling restart is fine: each instance has its own
   writer chain.
4. Verify with both keys. A key file for `--hmac-key-file` has two lines, the key id and then the base64 of the key's
   bytes. With the packaged server those bytes are the UTF-8 bytes of the environment variable's text. Pass one flag
   for each key:

   ```sh
   java -cp <classpath> io.github.aindriub.dataprism.audit.verify.AuditChainVerifierCli \
     /path/to/copy-of-audit-directory \
     --hmac-key-file audit-key-2026b.txt --hmac-key-file audit-key-2026a.txt
   ```

   Each writer must verify under exactly one of the keys. A writer that verifies under none is a break. The
   verifier prints `verified under key id:` for each writer. Check that the writers from before the change show the
   old key id and the writers started after it show the new one. See [the offline verifier](audit.md#the-offline-verifier).
5. Keep the old key's file with the segments it covers. Offline verification of an old segment needs it.
6. Keep the retired key configured until every segment written under it has been purged. Retention verifies expiring
   segments with the current key and every retired key and does not delete a segment written under any other key.
   If the old key is gone first, the purge stops and the `auditIntegrity` health contributor reports `DOWN` with
   `AUDIT_RETENTION_CHAIN_UNVERIFIED`. The newest segment under the old key is the last one the old key is
   needed for. It was written on the day of the change, and it can be purged once the retention period
   (`dataprism.audit.retention`, six months by default) has passed from that day.
7. When that newest segment has been purged, remove the retired entry and destroy the old key.

Two cautions. First, switching a directory that already holds unkeyed segments to a keyed chain leaves those
segments unpurgeable by the built-in retention, because retention does not delete a segment from a writer that
verifies under neither the current key nor a retired one. Plan that before turning the keyed chain on. Second, the
files do not record whether a chain is keyed or which key signed it: the verifier is told by the keys you pass.

Segments are named `audit-YYYY-MM-DD.log`, and `audit-YYYY-MM-DD.NNNN.log` (index 0001 and up) once
`dataprism.audit.segment-max-size` is set; the verifier's directory mode reads them in date and index order. A shipper
that tails the JSON projection must not read these; see [Shipping audit events to a log
stack](log-shipping.md).

## The cluster key

The cluster key matters only for `dataprism.hazelcast.topology: embedded` with `tcp-ip` or `kubernetes`, where it is
required, or with `none` when you choose to set it. A `none` member without keys draws random keys at each start and
keeps no state worth rotating. `single-node` and an application-supplied `PrivacyCluster` refuse the
`dataprism.hazelcast.security.*` keys; an application that supplies its own cluster holds the keys in its own code.

**Changing the cluster key is a planned full restart with state loss.** It is not rolling, and 0.8 has no rolling
procedure. The reasons are in the code:

- The identity cache and the re-identification index hold only `dps1` envelopes sealed under the current key. A
  member opens an envelope under any key it holds, but it writes under the current key only, and nothing in 0.8
  re-seals an entry under a newer key.
- The cluster's sentinel is written under the current key by the first member to start. A member that holds no key
  that opens it refuses with `CLUSTER_KEY_MISMATCH`.
- Cluster state is in memory only. Persistence and a map store are refused, so a full stop empties every map.

So there is no way to move the state to a new key. The procedure replaces the state instead.

The properties `cluster-key.next` and `cluster-key.previous` exist, and a member holding them accepts envelopes under
those ids for reading. 0.8 has nothing that writes under `next`, advances the sentinel or re-seals entries under a
new key, and no phased procedure that uses them is supported. The phased rotation, with a write key chosen from
member cards and a sweeper that re-seals entries, was deferred beyond 1.0. Leave `next` and `previous` unset in the
procedure below.

What is lost, so that you can decide whether the window is acceptable:

- **The re-identification index.** It is emptied. A pseudonym issued before the restart can no longer be
  re-identified. If the same subject is read again in a scope that is still open, the read writes the entry again;
  otherwise there is no way to rebuild it. Entries deleted from the index are lost for good.
- **Pending approvals.** They are lost and must be requested again.
- **Pauses.** A pause you rely on is lost and must be issued again after the restart.
- **Counters.** Per-subject read counts and caller rate windows start from zero, so a subject's read budget starts
  again.
- **The identity cache.** It is emptied and refilled by later calls. This costs computation only, and the
  pseudonyms are unchanged, because the pseudonymisation key is unchanged.

The procedure:

1. Choose a window. Tell the people who approve and re-identify, because requests they have open will be lost.
2. Generate the new cluster key and give it a new id with no dot in it, distinct from the name key's id and from
   every other key's. Store it where the deployment reads its keys.
3. Stop every member. A member left running keeps the old sentinel and the old entries, and a member started on the
   new key then refuses with `CLUSTER_KEY_MISMATCH`. On Kubernetes this means scaling the workload to zero, or a
   strategy that replaces every pod together, not a rolling update.
4. Set `dataprism.hazelcast.security.cluster-key.key-id` and its environment variable to the new key on every
   member. Keep the name key unchanged.
5. Start every member. The first to start writes the sentinel under the new key and the others open it.
6. Check that the members see each other and serve. The `clusterSecurity` health contributor, which is in readiness
   when health probes are enabled, must be `UP` on every member (see [Operating Data Prism across many
   sources](operations.md)). `CLUSTER_KEY_MISMATCH` means a member is still on the old key or an old member is still
   running; `UNSEALED_CLUSTER_STATE` means a pre-0.8 map still holds entries (see below).
7. Destroy the old cluster key. Nothing in 0.8 reads state sealed under it once the cluster has been replaced.

### Moving from 0.7: old and new map formats

0.7 and 0.8 keep their state in different maps and in different formats, so they cannot serve side by side.

- 0.7 used maps named `dataprism.identity`, `dataprism.reidentification`, `dataprism.budget`, `dataprism.oversight` and
  the approval and rate maps, with the subject ids in the keys and the values stored as they were.
- 0.8 uses maps whose names end in `.s1`. Their keys are HMACs under a subkey of the name key, so no subject id,
  principal, approval id or pseudonym appears in one, and the identity and re-identification values are `dps1`
  envelopes sealed under the cluster key.

A 0.8 member never reads the 0.7 maps for data. It asks only whether they hold entries. If one does, the member
refuses with `UNSEALED_CLUSTER_STATE`, or, for a pause written by 0.7, with `LEGACY_OVERSIGHT_STATE`. The one entry it
tolerates is the fence it writes itself, the key `all` holding `TRUE` in the old oversight map, which makes a 0.7
member read the cluster as paused.

The controlled cutover is therefore the same as the cluster-key change: stop every 0.7 member, then start the 0.8
members. A rolling upgrade is not supported, and this page does not present one as safe. Read counts are kept in a
different map in each generation, so members on the two versions would each count a subject's reads from zero, which
is the reset a rolling upgrade would produce. The 0.7 state is not copied and is not migrated. See [Migrating from
0.7.x to 0.8.0](migration-0.8.md#breaking-a-hazelcast-cluster-needs-a-full-restart-from-07).

## The name key

`dataprism.hazelcast.security.name-key` is a key of its own. It derives every map key and lock key in the cluster
(an HMAC under a subkey of it), so that no subject id, principal, approval id or pseudonym appears in a key. It is
never derived from the pseudonymisation key or from a cluster key, and the startup checks refuse a name key that
shares an id or its bytes with a cluster, pseudonymisation, audit or link key (`NAME_KEY_SHARED`). A name key whose
id equals a cluster key's id is refused separately, as `DUPLICATE_CLUSTER_KEY_ID`.

**Changing the name key is a planned full restart with state loss, exactly as for the cluster key.** A changed name
key gives every entry a different key, so the entries a member wrote earlier are unreachable by one that holds the new
key, and a member holding the old name key reads a disjoint set of keys. The name key does not rotate in 0.8: nothing
renames existing entries. The member cards also carry a check value for the name key's id, so a member that reuses an
id with other bytes is judged unverified.

The loss is the same as the cluster key's, including the re-identification index: after the restart, a pseudonym
issued before it can no longer be re-identified. To change only the name key, follow the steps of the cluster
procedure above with the name key in the place of the cluster key:

1. Choose a window and tell the people who approve and re-identify (cluster step 1).
2. Generate the new name key and give it a new id with no dot in it, distinct from every other key's id. Store it where
   the deployment reads its keys.
3. Stop every member (cluster step 3).
4. Set `dataprism.hazelcast.security.name-key.key-id` and its environment variable to the new name key on every member.
   Leave the cluster key exactly as it is: do not generate a new one, and do not destroy it. The members still need it to
   open the cluster's sentinel and entries, and a member that cannot resolve it fails to start.
5. Start every member and check `clusterSecurity` as in cluster step 6.
6. Destroy the old name key only. The cluster key stays in use.

If you change both keys, do it in one window with one full stop. Follow the cluster procedure, with two differences:
in its step 2 generate both keys, each with a new id that differs from the other's, and in its step 4 set both
`cluster-key.key-id` and `name-key.key-id` (and their variables) instead of keeping the name key unchanged. In step 7
destroy both old keys. Do not run members on different name keys.

## Link keys

`dataprism.links.keys.<key-id>` holds the link keys and `dataprism.links.key-id` names the one that seals new links.
Every key in `keys` is held for verification and only `key-id` seals. A link carries the id of the key that sealed
it, so a member opens a link by looking up that id. A key id is 1 to 16 characters of `A-Z`, `a-z`, `0-9`, `_` and
`-`. Page links need a link key even while links are off. A link key must differ from the pseudonymisation, cluster
and name keys.

The rotation has two phases, then a retirement, so that a rolling restart never produces a link that some member
cannot verify.

1. **Phase 1: hold the new key everywhere.** Generate the new key and add it to `dataprism.links.keys` with its
   reference, leaving `dataprism.links.key-id` on the old key. Restart every member, one at a time if you like.
   Confirm on each that the `linkKeys` health contributor lists the new id, or that the
   `dataprism.links.keys.loaded` gauge counts both keys. Do not go on until every member that must verify one another's
   links holds the new key.
2. **Phase 2: seal with it.** Set `dataprism.links.key-id` to the new key and keep the old key in `keys`. Restart
   the members, one at a time if you like. A member already sealing with the new key produces links that every other
   member can open, because phase 1 put the key on every member. Links sealed under the old key still verify.
3. **Retirement.** Wait until every link sealed under the old key has expired. That is the longer of
   `dataprism.links.lifetime` and `dataprism.paging.lifetime` after the last member stopped sealing with the old key;
   with `lifetime: scope`, a link lasts up to its scope's expiry. Then remove the old key from `keys`, restart, and
   **destroy the old key in the key store**. Removing it from memory only is not retirement: a copy that survives in
   a store or a backup can still forge a link that names the old key id.

A link sealed under a key id a member no longer holds is refused as `LINK_UNKNOWN_KEY`, and counts toward the failed
attempt limit for the caller. A key id the member holds but the provider cannot serve is refused as
`LINK_KEY_UNAVAILABLE`, which is not counted. Do not retire a key before the links under it have expired, or a
caller with an old link is counted against the limit. See [Retiring a link
key](operations.md#retiring-a-link-key) and [`dataprism.links`](configuration.md#dataprismlinks).

Links on a member that joins a cluster are refused unless a cluster-wide attempt limiter is supplied
(`MISSING_SHARED_LINK_LIMITER`), so in 0.8 a link-key rotation applies to instances that run on their own.

## What 0.8 does not do

- It has no rolling cluster-key rotation: no member-card write-key rule, no sentinel advance and no re-seal sweeper.
  Those are deferred beyond 1.0. A cluster-key change and a name-key change are planned full restarts.
- It runs one pseudonymisation key at a time under the starter, and it ships no command that derives an old pseudonym
  again from a retired key.
- It does not record the pseudonymisation key id in the audit trail.
- It has no way to keep the re-identification index across a cluster-key, name-key or pseudonymisation-key change.
- The packaged server reads keys from environment variables only.
