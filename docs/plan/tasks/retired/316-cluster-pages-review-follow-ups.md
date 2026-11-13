# 316 — Close the 312 and 14b review items on the cluster pages

**Repo:** `.`
**Release:** 0.8.0 (before the cut, 321)
**Executor:** `scribe`
**Depends on:** none
**Wave:** 1 (0.8.0 pre-cut, filed 2026-11-13)
**Owns:**
- docs/multiple-instances.md
- docs/plan/design/cluster-security.md
- docs/eu-ai-act.md

## Goal
The 0.8.0 cluster and audit pages still carry review findings that make small claims the code does not
support. Correct each named sentence, citing the code it now agrees with. Wording fixes only.

## Context
- PLAN.md, "Wave 14a follow-ups", the "from 312" and "eu-ai-act" bullets; "Wave 14b follow-ups" (e); and,
  on branch `origin/record-flakes` (PR #268), "Flake fixes 303/305/308 follow-ups", the 263-1 bullet.
- `docs/plan/tasks/retired/312-document-what-the-1-0-cluster-seals-verifies-and-trusts.md:80-100` (the two
  review rounds; the P3s and NOTE left open). Details of each finding are in the PR #258 review
  (`gh pr view 258 --comments`).
- `docs/multiple-instances.md:510` (the `endScope` sentence), `:119` ("The map exists"), `:103-130`
  ("Shared state"); `docs/plan/design/cluster-security.md:937` (T1, Hazelcast clients).
- `docs/eu-ai-act.md:47` ("The chain is unkeyed"); `docs/audit.md:978` (keyed chain, opt-in in 0.8.0, off
  by default).

## Acceptance
- [ ] AC1 The `endScope` sentence near `docs/multiple-instances.md:510` matches what `endScope` deletes and
  locks in the merged code; the PR body cites the code line.
- [ ] AC2 The cluster-security.md fence row and Write row state the consequence the 312 review asked for; the
  PR body quotes the finding and the new text.
- [ ] AC3 Where a page says "members are verified", it also says what a Hazelcast client is and is not
  verified as, consistent with T1 at cluster-security.md:937.
- [ ] AC4 "the map exists" reads "the map is configured" (or the code-accurate equivalent) wherever the 312
  NOTE named it.
- [ ] AC5 `docs/eu-ai-act.md:47` no longer calls the chain simply unkeyed: it says the chain is unkeyed by
  default and that a keyed chain is an opt-in in 0.8.0, linking `audit.md`.
- [ ] AC6 (14b e and 263-1) The "Shared state" claim about raw subject ids is either corrected with the line
  number and the code line it now matches, or, if no sentence on the page says it, the PR body says so and
  names the sentences checked.
- [ ] AC7 No sentence says "since 0.8"; 0.8.0 is unreleased, so it says "in 0.8.0".

## Checks
- `docs-site` command — AC1 to AC7
  run: `/Users/Andrew/workspace/data-prism/.venv-docs/bin/mkdocs build --strict && for f in $(printf '%s\n' docs-site/hooks/check_*.py | sort); do DP_REQUIRE_NO_STUBS=true python3 "$f" || exit 1; done`
- `scope` command — Owns
  run: `test -z "$(git diff --name-only origin/main...HEAD | grep -v -x -E 'docs/multiple-instances\.md|docs/plan/design/cluster-security\.md|docs/eu-ai-act\.md')"`
- `no-since` command — AC7
  run: `test -z "$(grep -n 'since 0\.8\b' docs/multiple-instances.md docs/eu-ai-act.md)"`
- `facts` read — AC1 to AC6
  how: reviewer checks each changed sentence against the code line the PR body cites.

## Out of scope
- Any other finding on these pages, and any change to what the cluster does.
- `docs/audit.md`, `docs/architecture.md`, `docs/key-rotation.md`, `docs/faq.md` (317).
- The version-literal sweep (321).

## Review round 1 — findings

Reviewed HEAD: 409eac41d43c4d02625a5dcf499c505f138768bb

- [P2] docs/multiple-instances.md:516-519 — The new paragraph keeps the stale clause "Ordinary writes to the identity and re-identification caches and the budget entries take no lock of their own … those are not bounded yet". In the code each of those writes takes its key's bounded lock first: identity store `ClusterLocks.acquire(identities, key, lockTimeout)` at CachingSyntheticValueSource.java:155 (held across :166 and :188); re-identification index `ClusterLocks.acquire(index, indexKey, lockTimeout)` at :204 (held across :213); budget read path HazelcastScopeBudget.java:163, reserve :237/:240 via `lockScope`/`lockSubject` (:502-508, :488-492), release :352/:355. `ClusterLocks.acquire` is a `tryLock` with wait and lease (ClusterLocks.java:68-79), consistent with docs/plan/design/cluster-security.md:468. route: implementer. input: an operator reads :516-519 and concludes a client holding a lock on an identity or budget key hangs ordinary reads unbounded → in fact each such call fails with CLUSTER_LOCK_TIMEOUT within `lock-timeout` (design test T11, cluster-security.md:948).
- [P3, fixed per owner decision 2026-11-12 on security-misleading docs] docs/multiple-instances.md:67-70 and docs/eu-ai-act.md:123-125 — The new client sentence lists what a keyless Hazelcast client can do as "read or write the unsealed maps", omitting that it can also delete sealed entries (a re-identification entry is lost for good; a deleted pause document causes OVERSIGHT_UNAVAILABLE) and hold locks. docs/plan/design/cluster-security.md:73 (A2): a client with the cluster name can "read, write, delete and lock any map entry". route: implementer — wording only, consistent with A2.

## Review round 2 — findings

Reviewed HEAD: 4c01387740353ec2e9c3ca8b8939ea8e51c91158

- [P2] docs/multiple-instances.md:518-521 — The fix now says every ordinary write to the budget entries takes its key's lock first and "does not hang". Not true for one budget write: once a scope has a total, `tryRead` releases the subject's lock and then adds to the scope total with no lock, `counts.executeOnKey(scopeKey, new IncrementIfPresent())` at data-prism-hazelcast/src/main/java/io/github/aindriub/dataprism/hazelcast/HazelcastScopeBudget.java:180-189 (comment at :181-182: "the add to the total waits whenever someone holds the total's key"); docs/plan/design/cluster-security.md:406 says such writes "wait without bound on a key another owner has locked". route: implementer — wording only: keep the bounded-lock sentence for identity and re-identification writes and for budget reserve, release and the subject update; add that a read's add to the scope total takes no lock and waits, without a bound, on a lock someone else holds on the total's key. input: a Hazelcast client locks the scope-total key and never releases it (A2, cluster-security.md:73) → any `tryRead` in that scope blocks at :189 with no `CLUSTER_LOCK_TIMEOUT`.

## Outcome

Landed 2026-11-13, PR #271, merge `4cb43696`, verified at `bbba592a`. The cluster pages (`docs/multiple-instances.md`, `docs/eu-ai-act.md`, `docs/plan/design/cluster-security.md`) no longer make the claims two review rounds showed the code does not support (the lock-bound clause on ordinary writes, the keyless client list). Tester PASS, reviewer APPROVE after two review rounds. Follow-ups are in `PLAN.md`.
