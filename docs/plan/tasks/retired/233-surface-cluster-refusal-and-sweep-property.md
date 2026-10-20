# 233 — Surface CLUSTER_OPERATION_REFUSED to callers and bind the approval sweep interval

**Repo:** .
**Release:** 0.7 (contract-register successor S2)
**Branch:** from `origin/plan/0.7.0` (79c8e7f8 or later).
**Depends on:** none (200, 216 and 232 have landed)
**Decided (owner, explicit, 2026-10-20):** OD-200-2 and OD-200-14, recorded in
`docs/plan/design/contract-register.md` section 14 and in `PLAN.md`.
**Owns:**
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/refusal/ClusterOperationRefusedException.java *(new; moved from the hazelcast module)*
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/refusal/PrivacyRefusedException.java *(insertion only: one constructor that takes a cause)*
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/refusal/ClusterOperationRefusedExceptionTest.java *(new)*
- data-prism-hazelcast/** (main and test)
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ToolAdmission.java *(the catch blocks of `admit` and the class Javadoc only)*
- data-prism-security/src/test/java/io/github/aindriub/dataprism/security/ToolAdmissionTest.java *(insertions only)*
- data-prism-reidentification/src/main/java/io/github/aindriub/dataprism/reidentification/ReidentificationService.java *(the `catch (RuntimeException …)` blocks that answer `REIDENTIFICATION_UNAVAILABLE` only)*
- data-prism-reidentification/src/test/java/io/github/aindriub/dataprism/reidentification/ReidentificationServiceTest.java *(insertions only)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/OversightOperatorController.java *(the catch blocks and the `unavailable` helper that answer `OVERSIGHT_UNAVAILABLE` for a store or state failure only; not `denied`)*
- data-prism-server/src/main/java/io/github/aindriub/dataprism/server/operator/ReidentificationOperatorController.java *(`statusOf` only)*
- data-prism-server/src/test/java/io/github/aindriub/dataprism/server/operator/ClusterRefusalOperatorTest.java *(new)*
- data-prism-mcp/src/test/java/io/github/aindriub/dataprism/mcp/ClusterOperationRefusalToolTest.java *(new)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/HazelcastProperties.java *(insertions, plus `clusterSettingsSet()` if the new key joins it)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/validation/DataPrismPropertiesValidator.java *(`validateCluster`'s supplied-cluster branch at `:233-238` and the `dataprism.hazelcast` checks at `:554-573` only)*
- data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/ClusterBackedState.java *(the `dataPrismPrivacyCluster` bean method at `:47-60` only)*
- data-prism-spring-boot-autoconfigure/src/test/resources/io/github/aindriub/dataprism/spring/boot/frozen-property-names.txt *(insertion only: one line)*
- data-prism-spring-boot-autoconfigure/src/test/java/io/github/aindriub/dataprism/spring/boot/ApprovalSweepIntervalConfigurationTest.java *(new)*
- docs/configuration.md *(the `dataprism.hazelcast` row at `:138` and the `dataprism.hazelcast.*` property rows at `:185-202` only)*
- docs/multiple-instances.md *(the "Configuration" table, the "Refusals" table, "A lock someone holds" at `:285-301`, the creation-refusal paragraph at `:339-345` and the sweep text at `:419-432` only)*
- docs/tools.md *(the admission-code rows at `:511-512` only)*
- docs/reidentification.md *(the "Refusal codes" table at `:44-59` and the "Other codes" paragraph at `:147-150` only)*
- docs/plan/design/contract-register.md *(only the rows and paragraphs that name `CLUSTER_OPERATION_REFUSED`, the `CLUSTER_LOCK_TIMEOUT` surfacing, `dataprism.approval.pending.sweep-seconds`, the new key or the new startup code)*
- docs/plan/tasks/233-surface-cluster-refusal-and-sweep-property.md *(Outcome section only)*

Disjoint from task 201, which owns `CHANGELOG.md`, `docs/migration-0.7.md`, `mkdocs.yml`, `docs/architecture.md`,
`docs/faq.md` and three other `docs/configuration.md` passages (`:132`, the `:448` alt text, the `:534`
`max-response-size` row). 201 depends on this task and takes its CHANGELOG lines and migration note from this
task's Outcome. Task 215 writes only task briefs. The bean inventory records no bean-method parameters, so the
`ClusterBackedState` edit changes no inventory file.

## Goal
`CLUSTER_OPERATION_REFUSED` (task 216) exists, but no caller can see it. Every path turns it into a generic code,
the throw sites drop the Hazelcast cause, and nothing logs it. This task makes every path answer with the code
itself, as `CLUSTER_LOCK_TIMEOUT` already does at admission and for the read budget, with the cause chained and
logged and no data in the message. The class moves to `core.refusal`. In the same task, the unreleased Hazelcast
config property `dataprism.approval.pending.sweep-seconds` (task 232) is replaced by the Spring-bound, validated and
frozen `Duration` key `dataprism.hazelcast.approval-sweep-interval`.

## Context
- `docs/plan/design/contract-register.md` section 3.2 (`:288-318`). It has a table of where the code surfaces today, the
  OD-200-2 decision (`:1348-1354`) and the OD-200-14 decision (`:1444-1450`). Section 6.3 `:923` and
  section 13 `:1319` cover the old property. Sections 0 `:35`, 11 `C2` `:1245` and 12 `D9` `:1282` also name it.
- How `CLUSTER_LOCK_TIMEOUT` reaches callers today, which is the pattern to copy:
  - Admission: `data-prism-security/.../ToolAdmission.java:82-83` has its own catch before the generic
    `RuntimeException` catch at `:84-85`. `data-prism-mcp/.../ToolCalls.java:72-104` then audits
    `DENY:<code>` and returns the code as the refusal text.
  - Read budget: `data-prism-hazelcast/.../HazelcastScopeBudget.java:62-70` rethrows the lock timeout. Any other
    failure goes to `refuse(...)` (`:93-97`), returns `false` and becomes `SCOPE_READ_BUDGET`.
    `data-prism-orchestration/.../DefaultContextOrchestrator.java:176-185,258-276,327-332` audits any
    `PrivacyRefusedException` as `DENY:<code>` with `merged:<refused>` = `REFUSED`.
    `GetEntityContextTool.java:249-255` returns it as `refused: <code> at <path>`.
  - Re-identification: `ReidentificationService.java:77-79,86-88,107-108,127-128,159-160` answers
    `REIDENTIFICATION_UNAVAILABLE` for both cluster exceptions. `ReidentificationOperatorController.java:117-128`
    maps codes to HTTP statuses, and an unlisted code falls to 500.
  - Operator approve and reject: `OversightOperatorController.java:128-131,141-145,158-165,180-187,214-225` and the `unavailable` helper at `:254-257` answer
    503 `OVERSIGHT_UNAVAILABLE` for both. `docs/multiple-instances.md:301` documents that.
- Where `CLUSTER_OPERATION_REFUSED` is thrown, always without a cause:
  - `ClusterLocks.java:62-64`. A lock or lease that Hazelcast rejects. This reaches the store, the read budget
    and the caller rate limiter.
  - `HazelcastApprovalStore.java:305-313` (`cluster(...)`) and `:319-329` (`HeldLocks.take`).
- `data-prism-core/.../refusal/ClusterLockTimeoutException.java` is the sibling to sit beside.
- `data-prism-core/.../refusal/PrivacyRefusedException.java:21` has no constructor that takes a cause.
- `HazelcastScopeBudget.java:93-97` shows the log shape to follow: the class name only, never the message.
- Sweep property:
  - `PendingSweeper.java:34-78` holds the old property, its parse and its 1 to 86400 s bounds.
  - `PrivacyCluster.java:100-105` builds the sweeper in the constructor, and `:364` validates the old property.
  - The public factories are `PrivacyCluster.java:127-150,294-329`.
  - `ApprovalRetentionTest.java:333-364` sets and tests the old property.
- Spring side:
  - `HazelcastProperties.java:30-65` is the `lock-timeout` field, its `…Set` flag and Javadoc. The Javadoc becomes
    the metadata description, which `ConfigurationMetadataDocumentedTest` requires.
  - `DataPrismPropertiesValidator.java:554-557` (`INVALID_LOCK_TIMEOUT`), `:568-573` (single-node
    `CLUSTER_SETTINGS_IGNORED`) and `:233-238` (application-supplied `PrivacyCluster`).
  - `ClusterBackedState.java:47-60`.
- Test patterns:
  - `ClusterLockTimeoutConfigurationTest.java:73-105,146-177` for refusal and for proving a bound value reaches
    its consumer.
  - `PropertyNamesFrozenTest` and `frozen-property-names.txt:44` for the line format
    (`… | java.time.Duration | rw | PT2S`).
  - `AutoConfiguredBeanInventoryTest` with `bean-inventory/*.txt`.
  - `ToolAdmissionEnforcementTest` in data-prism-mcp for a tool call with a stubbed admission store.
- `docs/conventions.md` covers refusal-code naming and the privacy rules a diff must satisfy.

## Required approach
1. **Move the class.** Create `io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException` with the
   same `CODE` constant (`"CLUSTER_OPERATION_REFUSED"`), path `$` and message. Delete the hazelcast-module class.
   Add no alias or deprecated subclass: it was never released. Give `PrivacyRefusedException` a constructor
   `(code, path, detail, cause)`. The new class takes the cause through it. Its Javadoc names no Hazelcast type,
   because core has no Hazelcast dependency.
2. **Chain and log at the throw sites.** Every production `new ClusterOperationRefusedException(...)` passes the
   caught exception as the cause. At each throw site the hazelcast module writes exactly one WARN line per refusal. The
   line contains `CLUSTER_OPERATION_REFUSED` and the cause's simple class name, and nothing else from the cause: no
   message and no stack trace, because a Hazelcast message can echo a key. A refusal that passes through
   `HeldLocks.take` or `cluster(...)` unchanged is not logged a second time.
3. **Surface it on every path, like the lock timeout.**
   - `ToolAdmission.admit`: catch `ClusterOperationRefusedException` beside `ClusterLockTimeoutException` and refuse with
     its `CODE`. This covers approval creation, `findPending`, `consumeApproved` and the caller rate limiter.
     `ToolCalls` then audits `DENY:CLUSTER_OPERATION_REFUSED` unchanged.
   - `HazelcastScopeBudget.tryRead`: a `ClusterOperationRefusedException` from taking the lock propagates, like
     `ClusterLockTimeoutException`. It is not turned into `false`. Every other budget failure still refuses the read
     as `SCOPE_READ_BUDGET`.
   - `ReidentificationService`: in each catch that answers `REIDENTIFICATION_UNAVAILABLE` today, a
     `ClusterOperationRefusedException` or a `ClusterLockTimeoutException` answers with its own code instead. The
     method audits it as `DENY:<code>` through the existing `refuse`/`refuseApproval`. Other failures keep
     `REIDENTIFICATION_UNAVAILABLE`.
   - `ReidentificationOperatorController.statusOf`: both cluster codes map to 503.
   - `OversightOperatorController`: in each catch that answers `OVERSIGHT_UNAVAILABLE` today, either cluster exception
     answers 503 with its own code, and `recordDenial` records that code. Other failures keep `OVERSIGHT_UNAVAILABLE`.

   The lock timeout joins the re-identification and operator paths on purpose. OD-200-2 option (b) names those two
   classes, and surfacing one sibling code there without the other would make the two codes inconsistent. The mechanism is
   the implementer's choice: separate catches, or a shared helper in `core.refusal`. Any helper is
   package-neutral and names no Hazelcast type.
4. **Fail closed throughout.**
   - Every surfaced refusal is a refusal: nothing is admitted, read or returned, and every path writes the same
     `DENY:<code>` audit event it writes for `CLUSTER_LOCK_TIMEOUT`. The orchestrator path keeps
     `merged:<refused>` = `REFUSED`.
   - Where the audit write for the denial fails, the existing behaviour holds: `AUDIT_UNAVAILABLE` on
     re-identification, and the operator controller's existing audit failure.
   - A caller never receives an exception message or a cause, only the code.
5. **The sweep property.**
   - Add `dataprism.hazelcast.approval-sweep-interval` to `HazelcastProperties`. It is a `Duration`, default
     `Duration.ofMinutes(5)`, with a `…Set` flag like `lock-timeout` and Javadoc that becomes the metadata description.
   - Bounds are `PT1S` to `P1D`, inclusive: the old 1 to 86400 s range, as recommended in OD-200-14 (b). A null,
     zero, negative or out-of-range value is refused at startup with the new code `INVALID_APPROVAL_SWEEP_INTERVAL`.
     A value that does not parse as a `Duration` fails Spring binding, as `lock-timeout` does today.
   - With `topology: single-node`, or with an application-supplied `PrivacyCluster` bean, setting the key is refused
     with `CLUSTER_SETTINGS_IGNORED`. The interval is fixed when the cluster is built, so the key would have no effect
     there.
   - In data-prism-hazelcast, the interval becomes a constructor input of `PrivacyCluster`. Add the overloads
     `embedded(ClusterMembership, boolean, OptionalInt, Duration)`, `embedded(Config, boolean, OptionalInt, Duration)`
     and `using(HazelcastInstance, boolean, Duration)`. The existing factories delegate with `PT5M`.
   - Add a public accessor `Duration approvalSweepInterval()`. The factories refuse an interval outside the bounds
     with `IllegalArgumentException`, as `ClusterLocks.checked` does.
   - Delete `PendingSweeper.INTERVAL_PROPERTY`, `intervalOf(Config)` and the `configure` call at `PrivacyCluster.java:364`.
     A `Config` carrying the old name is neither read nor refused.
   - `ClusterBackedState.dataPrismPrivacyCluster` passes the bound value.

## Acceptance
- [ ] `git grep -n 'hazelcast.ClusterOperationRefusedException'` and
      `git grep -n 'sweep-seconds' -- ':!docs/plan/tasks/retired' ':!docs/plan/HISTORY*'` return nothing. The register
      lists the old name only in section 10 ("Withdrawn and superseded names"), as replaced by OD-200-14.
- [ ] `io.github.aindriub.dataprism.core.refusal.ClusterOperationRefusedException` exists. `CODE` is
      `CLUSTER_OPERATION_REFUSED`, and `getMessage()` is `CLUSTER_OPERATION_REFUSED at $: a cluster operation was
      refused`. `ClusterOperationRefusedExceptionTest` shows that `getCause()` returns the cause passed in, and that
      the message does not contain the cause's message (a sentinel string).
- [ ] Each test below is new or inserted, and each fails on `79c8e7f8`. Record the red run of each in the Outcome,
      taken with a temporary commit that reverts the main-code change (not `git stash`):
  - hazelcast module, real member, `hazelcast.lock.max.lease.time.seconds` below five times the lock timeout:
    - `HazelcastScopeBudget.tryRead` throws `ClusterOperationRefusedException` with a non-null cause. Before the
      change it returns `false`.
    - `HazelcastCallerRateLimiter.tryAcquire` throws it with a non-null cause.
    - `HazelcastApprovalStore.create`, `approve` and `consumeApproved` each throw it with a non-null cause.
  - hazelcast module: one throw site, driven with a cause whose message holds a sentinel, writes exactly one WARN
    line containing `CLUSTER_OPERATION_REFUSED` and the cause's simple class name. The captured log output contains
    neither the sentinel nor a stack trace.
  - `ToolAdmissionTest`: a store whose `create`, `findPending` or `consumeApproved` throws the exception, and a
    limiter whose `tryAcquire` throws it, each give `AdmissionDecision.refuse("CLUSTER_OPERATION_REFUSED")`. Before
    the change they gave `OVERSIGHT_UNAVAILABLE`.
  - `ClusterOperationRefusalToolTest` (mcp):
    - A `get_entity_context` call whose admission store throws the exception returns the refusal text
      `CLUSTER_OPERATION_REFUSED` and audits `DENY:CLUSTER_OPERATION_REFUSED`.
    - A call whose `ScopeBudget.tryRead` throws it returns `refused: CLUSTER_OPERATION_REFUSED at $` and audits
      `DENY:CLUSTER_OPERATION_REFUSED` with `merged:<refused>` = `REFUSED`. No source is called.
  - `ReidentificationServiceTest`:
    - `request` (four-eyes), `approve` and `collect` each answer `Refused("CLUSTER_OPERATION_REFUSED")` and audit
      `DENY:CLUSTER_OPERATION_REFUSED` when the store throws the exception. They answer `CLUSTER_LOCK_TIMEOUT` when it
      throws `ClusterLockTimeoutException`.
    - With the audit sink failing, the answer is `AUDIT_UNAVAILABLE`.
    - A plain `IllegalStateException` still answers `REIDENTIFICATION_UNAVAILABLE`.
  - `ClusterRefusalOperatorTest` (server):
    - `POST /operator/approvals/{id}/approve` and `/reject`, over a store that throws either cluster exception,
      answer 503 with body `{"code":"<that code>"}` and audit a denial with that code.
    - A re-identification endpoint over such a store answers 503 with the same body.
    - A plain `IllegalStateException` still answers 503 `OVERSIGHT_UNAVAILABLE`.
- [ ] `ApprovalSweepIntervalConfigurationTest` (autoconfigure):
  - Unset, the `PrivacyCluster` bean's `approvalSweepInterval()` is `PT5M`.
  - `dataprism.hazelcast.approval-sweep-interval=PT30S` binds and reaches it, and `PT1S` and `P1D` are accepted.
  - `PT0S`, `-PT1S`, `PT0.5S` and `P1DT1S` each refuse startup with `INVALID_APPROVAL_SWEEP_INTERVAL`.
  - Setting it with `single-node`, or with an application-supplied `PrivacyCluster`, refuses with
    `CLUSTER_SETTINGS_IGNORED`.
- [ ] `ApprovalRetentionTest`'s schedule test sets the interval through the new factory overload, not a `Config`
      property. Its validation test is replaced by one on the factory bounds.
- [ ] `frozen-property-names.txt` gains exactly one line, `dataprism.hazelcast.approval-sweep-interval |
      java.time.Duration | rw | PT5M`, and no other line changes. No `bean-inventory/*.txt` file and no
      `configuration-metadata-gaps.txt` line changes. `git diff --stat` against the base shows that.
- [ ] Docs are true of the code. `docs/configuration.md` has the property row with default `PT5M` and its bounds,
      and `INVALID_APPROVAL_SWEEP_INTERVAL` in the `:138` refusal list. `docs/multiple-instances.md` has:
  - the property row and the refusal row;
  - the sweep text naming the new key;
  - "A lock someone holds" and the creation-refusal paragraph stating which callers now see
    `CLUSTER_OPERATION_REFUSED` (tool calls, reads, re-identification and the operator API). This replaces the
    `:301` "keeps answering `503 OVERSIGHT_UNAVAILABLE`" line.

  `docs/tools.md` lists `CLUSTER_OPERATION_REFUSED` beside `CLUSTER_LOCK_TIMEOUT` with what a client should do
  (retry later; tell an operator if it persists). `docs/reidentification.md` lists both cluster codes and their 503
  status. No sentence says "compliant".
- [ ] `docs/plan/design/contract-register.md` is updated:
  - Section 3.2's code row points at the core class.
  - Its "Where it surfaces today" table states the new behaviour, and its paragraph no longer says no caller can
    see the code.
  - Sections 3.5 and 6.1 gain `INVALID_APPROVAL_SWEEP_INTERVAL` and `dataprism.hazelcast.approval-sweep-interval`
    (state `shipped*`, 0.7.0*).
  - The 6.3, 13, C2, D9 and section-0 entries record the resolution.
- [ ] `./mvnw verify` over the full reactor exits 0, and `./mvnw -Prelease -DskipTests package` exits 0. Any
      single-module run uses `-pl <module> -am`, because `~/.m2` holds a stale 0.6.0 core jar. Record the test
      counts in the Outcome.
- [ ] The Outcome has a section "For task 201" that contains:
  - proposed `CHANGELOG.md` lines: `### Changed` for the code now reaching callers on each path, and for the lock
    timeout on the re-identification and operator paths; `### Added` for the key and its startup code;
  - a migration-note paragraph for `docs/migration-0.7.md`, by audience. Client authors: a new refusal code to
    treat as retryable. Operators: the new 503 codes on the operator API, and the key with its default ("unchanged
    unless you set it"). Extension authors: the class moved to `core.refusal`;
  - one line stating that `dataprism.approval.pending.sweep-seconds` was never released, so it gets no migration
    entry.

  It does not edit `CHANGELOG.md`.

## Out of scope
- `CHANGELOG.md`, `docs/migration-0.7.md`, `docs/architecture.md`, `docs/faq.md`, `mkdocs.yml` and the 201-owned
  passages of `docs/configuration.md` (task 201).
- Changing what `HazelcastScopeBudget` does on failures other than the lock. An unreachable cluster still refuses
  the read as `SCOPE_READ_BUDGET`.
- Giving `ClusterLockTimeoutException` a cause, or changing where or how it is logged.
- `ToolCalls.admit`'s own generic catch in data-prism-mcp. Admission already maps the exception before it gets there.
- The 232 follow-ups in `PLAN.md`: re-reading with `find` on an uncertain outcome, the unused `endScope` and
  `forgetScope`, the `arg$1` reflection, and forget during a split.
- The other register successors (S1, S3 to S6), and documenting codes the shipped pages lack apart from the two
  cluster codes.
- Any new metric, and any change to `PrivacyMetrics`.
- Any other `@Bean` method, and any other validator check.

## Rules
- Never read `~/.m2/settings.xml`, `~/.m2/settings-security.xml`, `~/.gnupg` or any other credential file.
- Test identities, scopes and IDs are invented and neutral (`alice`, `scope-a`, `x-1`). Use no real person,
  organisation, workplace or business domain.
- In anything you write, say that the product "supports" a requirement, never that it is "compliant".
- No fixed sleeps in new tests. Wait on latches or on observable state.
- No exception message, log line or audit value added by this task may carry a key, scope, principal, approval id
  or Hazelcast message text.

## Outcome

**Status: done.** Branch `task/233-cluster-refusal`, cut from `7da83eaa`. Commits, in order: `1b498978` move the class and add the
cause constructor; `8dfd8f8e` the failing tests plus behaviour-preserving seams; `90b66240` hazelcast (chain, log, budget,
`PrivacyCluster` interval input); `1b588ff5` admission, re-identification and operator paths; `a6747a16` the Spring key, its
validation and the frozen line; then the docs commit.

### Decisions

- **D-0.7-HZ-3 (owner, explicit, 2026-10-20).** Both `CLUSTER_LOCK_TIMEOUT` and `CLUSTER_OPERATION_REFUSED` are surfaced on the
  re-identification and operator paths, as step 3 of the brief specifies, so the two cluster codes behave the same on every
  path. **This changes documented responses**: `docs/multiple-instances.md:301` said the operator API "keeps answering `503
  OVERSIGHT_UNAVAILABLE`", and re-identification answered `REIDENTIFICATION_UNAVAILABLE` for a lock timeout. Both now answer the
  cluster code (503 with `{"code":"CLUSTER_LOCK_TIMEOUT"}` or `{"code":"CLUSTER_OPERATION_REFUSED"}`); any other failure keeps
  the old code. Task 201 gets a `Changed` CHANGELOG line for it (below).
- Mechanism: no shared helper in core. Each of `ToolAdmission` (a catch), `ReidentificationService` (`unavailableCode`) and
  `OversightOperatorController` (`unavailableCode`) maps the two classes; `ReidentificationOperatorController.statusOf` maps both
  codes to 503. The log seam is `hazelcast:ClusterRefusals` (package-private), which builds the exception and writes the one
  WARN line (`CLUSTER_OPERATION_REFUSED: a cluster operation was refused; cause <SimpleClassName>`).
- `OversightOperatorController`: besides approve and reject, the same mapping applies to the other catches that answered
  `OVERSIGHT_UNAVAILABLE` for a store or state failure (pause, resume, `state`, `approvals`, `approval`, and the `toolCall`
  lookup), as the brief says "each catch". `denied` is untouched.
- Sweep interval: the validator checks the key in the main `dataprism.hazelcast` block (`INVALID_APPROVAL_SWEEP_INTERVAL`) and
  adds `approvalSweepIntervalSet()` to the two `CLUSTER_SETTINGS_IGNORED` conditions (single-node, and the supplied-cluster
  branch). `clusterSettingsSet()` is unchanged, so the supplied-cluster branch does not refuse `lock-timeout`, as before.

### Deviations and things to know (read these)

1. **Audit-failure wording in the brief.** The brief says that with the audit sink failing on a cluster refusal "the answer is
   `AUDIT_UNAVAILABLE`" and calls that existing behaviour. It is not: `ReidentificationService.refuse` and `refuseApproval`
   swallow a failed denial audit ("still a refusal") and return the refusal code, and only the ALLOW paths answer
   `AUDIT_UNAVAILABLE`. Changing that would mean editing `refuse`, which is outside `Owns`, and would change every denial. I kept
   the existing disposition: with the sink down, a cluster refusal still answers its own code and resolves nothing
   (`aClusterRefusalWithTheAuditSinkDownStillRefusesAndResolvesNothing`). The operator controller's `recordDenial` likewise
   still swallows. The existing `AUDIT_UNAVAILABLE` tests are unchanged and pass.
2. **Acceptance grep for `sweep-seconds`.** It cannot return nothing: the old name remains in the owner's OD-200-14 decision
   record (register section 14), in `PLAN.md:160` (not in `Owns`), in register section 10 (as required) and in this task file.
   Everything else is gone, including all code and all shipped docs. `git grep 'hazelcast.ClusterOperationRefusedException'`
   returns only the task file.
3. **One new test passes on the base.** `ClusterOperationRefusalToolTest.aReadBudgetThatRefusesTheClusterOperation...` (a
   `ScopeBudget` that throws the exception) already passes at `79c8e7f8`, because the orchestrator audits any
   `PrivacyRefusedException`; it is a characterisation test. The real defect (the Hazelcast budget swallowing the exception and
   answering `false`) is covered by `ClusterOperationRefusalTest.theReadBudgetPropagatesARefusedLockInsteadOfAnswering`, which is red.
4. **Seams in the red commit.** Because tests for the new API cannot compile against the base, commit `8dfd8f8e` also holds
   behaviour-preserving seams: `ClusterRefusals.refused` (builds the exception with a `null` cause and logs nothing), the three
   `PrivacyCluster` overloads (which ignore the interval), `PrivacyCluster.approvalSweepInterval()` (always `PT5M`) and the
   `HazelcastProperties` getter. The red run below is that exact commit. Each failure is a behavioural assertion, not a compile error,
   except the ones that note otherwise.
5. **Register edits beyond the named rows.** Inserting one line into `frozen-property-names.txt` shifted every later `FZ:<line>`
   reference in `contract-register.md` by one; I bumped them (mechanical, no other change). Other `module:File.java:line` references
   in the register drift with any code change and were left alone, except the rows this task names.
6. The old `ApprovalRetentionTest` validation test (property parse) is replaced by a factory-bounds test, and a new test shows a
   `Config` carrying the old property name is neither read nor refused (the literal is split in the source so the acceptance grep
   stays clean).
7. `PrivacyCluster.using(instance, ...)` with an out-of-range interval throws `IllegalArgumentException` before touching the
   instance, which is left running (as its other refusals already do).

### Red run (commit `8dfd8f8e`, which holds the new tests and none of the fixes)

Run from a throwaway worktree of that commit with
`./mvnw -B test -pl <seven modules> -am -Dtest=<eight classes> -Dmaven.test.failure.ignore=true`. Every named assertion failed
for the reason the brief gives:

- `ClusterOperationRefusalTest` (hazelcast, real member, `hazelcast.lock.max.lease.time.seconds=1`): 9 of 9 fail.
  `theReadBudgetPropagatesARefusedLockInsteadOfAnswering` and `aRefusedLockOnTheBudgetAndTheLimiterEachLogOnce`:
  `Expecting code to raise a throwable.` (the budget returned `false`). `createRefusesWithTheCause`, `approveRefusesWithTheCause`,
  `consumeApprovedRefusesWithTheCause`, `theCallerRateLimiterPropagatesARefusedLock`: `Expecting actual not to be null` (the
  cause). The log tests: `Expected size: 1 but was: 0 in: []` (no WARN line) and `aBuiltRefusalLogsTheCodeAndTheCauseClassOnly` null cause.
- `ToolAdmissionTest`: `aRefusedClusterOperationInEachApprovalStoreCallRefusesWithItsOwnCode:257 create ==> expected:
  <CLUSTER_OPERATION_REFUSED> but was: <OVERSIGHT_UNAVAILABLE>` (the loop stops at the first of `create`, `findPending`,
  `consumeApproved`) and `aRefusedClusterOperationInTheRateLimiterRefusesWithItsOwnCode:267` with the same message.
- `ClusterOperationRefusalToolTest`: the admission test failed (`expected: "CLUSTER_OPERATION_REFUSED" but was:
  "OVERSIGHT_UNAVAILABLE"`); the budget test passes on the base (deviation 3).
- `ReidentificationServiceTest`: 5 of the 6 new tests fail with `expected: Refused[code=CLUSTER_OPERATION_REFUSED] but was:
  Refused[code=REIDENTIFICATION_UNAVAILABLE]` (open, approve on `find` and `approve`, collect on `find` and `consumeApproved`, the
  non-four-eyes lookup, and the audit-sink-down case). The sixth, "any other failure is still `REIDENTIFICATION_UNAVAILABLE`", is
  a guard and passes on both sides.
- `ClusterRefusalOperatorTest`: 5 of 7 fail with `expected: "{"code":"CLUSTER_OPERATION_REFUSED"}" but was:
  "{"code":"OVERSIGHT_UNAVAILABLE"}"` (or `REIDENTIFICATION_UNAVAILABLE` for the re-identification endpoint); the two guards pass.
- `ApprovalSweepIntervalConfigurationTest`: 5 of 7 fail (`expected: 30S` / `1S`: the bound value never reached the cluster;
  the refusals `INVALID_APPROVAL_SWEEP_INTERVAL` and the two `CLUSTER_SETTINGS_IGNORED` cases were not raised). The default and
  the non-duration binding failure pass on both sides.
- `ApprovalRetentionTest`: 3 new or changed tests fail (`theScheduledSweepRemovesAnExpiredEntry...` and
  `theSweepIntervalIsAFactoryInputWithinItsBounds` at `expected: 1S`; the old-property test with `IllegalArgumentException:
  ... must be a whole number of seconds from 1 to 86400`).
- `ClusterOperationRefusedExceptionTest` does not exist at `79c8e7f8` (the class is new); it first passes at `1b498978`.

### Verification

- `./mvnw -B verify` over the full reactor (20 modules, 5 min 53 s): **exit code 0**, `BUILD SUCCESS`. From the XML reports:
  surefire 225 files, **1978 tests, 0 failures, 0 errors, 0 skipped**; failsafe 5 files, **17 tests, 0 failures, 0 errors,
  0 skipped**.
- `./mvnw -B -Prelease -DskipTests package`: **exit code 0**, `BUILD SUCCESS`.
- New or changed test classes, all green: `ClusterOperationRefusedExceptionTest` 2, `ClusterOperationRefusalTest` 9,
  `ApprovalRetentionTest` 19, `ToolAdmissionTest` 22 (2 new), `ClusterOperationRefusalToolTest` 2,
  `ReidentificationServiceTest` 25 (6 new), `ClusterRefusalOperatorTest` 7, `ApprovalSweepIntervalConfigurationTest` 7.
  Also green and unchanged: `PropertyNamesFrozenTest`, `ConfigurationMetadataDocumentedTest`, `AutoConfiguredBeanInventoryTest`.
- `git diff --stat 7da83eaa..HEAD`: `frozen-property-names.txt` +1 line; no `bean-inventory/*.txt` file and no
  `configuration-metadata-gaps.txt` change.
- No fixed sleeps in any new test; the sweep schedule test waits on observable state (`awaitTrue`, the existing helper).

### For task 201

Proposed `CHANGELOG.md` lines (do not edit `CHANGELOG.md` from this task):

```
### Changed
- A tool call that the cluster refuses an operation for now returns `CLUSTER_OPERATION_REFUSED` (audited
  `DENY:CLUSTER_OPERATION_REFUSED`) instead of `OVERSIGHT_UNAVAILABLE`, and a read whose cluster budget lock the cluster rejects
  returns `refused: CLUSTER_OPERATION_REFUSED at $` instead of `SCOPE_READ_BUDGET`. Any other budget failure still refuses the read
  as `SCOPE_READ_BUDGET`.
- Re-identification (request, approve and collect) answers `CLUSTER_OPERATION_REFUSED` or `CLUSTER_LOCK_TIMEOUT` as its refusal
  code instead of `REIDENTIFICATION_UNAVAILABLE`, and the operator API answers them with `503` and `{"code":"<code>"}` instead of
  `503 OVERSIGHT_UNAVAILABLE` (approve, reject, pause, resume and the state and approval reads) and instead of
  `REIDENTIFICATION_UNAVAILABLE` (the re-identification endpoints). Any other failure keeps the old code. This changes the
  documented response in `docs/multiple-instances.md` ("The operator API keeps answering `503 OVERSIGHT_UNAVAILABLE`").
- `ClusterOperationRefusedException` moved from `io.github.aindriub.dataprism.hazelcast` to `io.github.aindriub.dataprism.core.refusal`;
  it carries its cause, and the cluster refusal is logged once at `WARN` with the cause's class name only.

### Added
- `dataprism.hazelcast.approval-sweep-interval`, a `Duration` (default `PT5M`, `PT1S` to `P1D`) for how often each member sweeps
  pending approvals and approval-id claims. A value outside the range is refused at startup with
  `INVALID_APPROVAL_SWEEP_INTERVAL`; it is refused with `CLUSTER_SETTINGS_IGNORED` under `single-node` and beside an
  application-supplied `PrivacyCluster`. `PrivacyCluster.embedded(..., Duration)`, `PrivacyCluster.using(instance, boolean,
  Duration)` and `PrivacyCluster.approvalSweepInterval()` are new.
```

Migration-note paragraph for `docs/migration-0.7.md`, by audience:

- **Client authors.** `CLUSTER_OPERATION_REFUSED` is a new refusal code, and it is retryable: the cluster rejected an operation
  (for example a lock or its lease), nothing was counted or changed, and a later retry may succeed. Treat it like
  `CLUSTER_LOCK_TIMEOUT`, which now also reaches re-identification callers. A read the cluster budget cannot lock reports
  `CLUSTER_OPERATION_REFUSED` rather than `SCOPE_READ_BUDGET`.
- **Operators.** The operator API can now answer `503` with `{"code":"CLUSTER_LOCK_TIMEOUT"}` or
  `{"code":"CLUSTER_OPERATION_REFUSED"}` where it answered `OVERSIGHT_UNAVAILABLE` (and `REIDENTIFICATION_UNAVAILABLE` on the
  re-identification endpoints), and the denial is audited with that code. The cause is logged once at `WARN` as the code and the
  exception's class name only, so a rejected lease (`hazelcast.lock.max.lease.time.seconds` below five times `lock-timeout`) is
  now visible in the log. The new key `dataprism.hazelcast.approval-sweep-interval` defaults to `PT5M`: unchanged unless you set it.
- **Extension authors.** `ClusterOperationRefusedException` moved to `io.github.aindriub.dataprism.core.refusal`. It was never
  released, so there is no alias. Code that implements `ApprovalStore`, `CallerRateLimiter` or `ScopeBudget` over its own cluster
  can throw it (or `ClusterLockTimeoutException`) to have the code reach the caller.

`dataprism.approval.pending.sweep-seconds` was never released, so it gets no migration entry.

### Owner waivers and follow-ups (2026-10-20)

- **Waiver 1 (owner).** The acceptance item "with the audit sink failing, the answer is `AUDIT_UNAVAILABLE`" is waived. The brief was
  wrong about the existing behaviour (deviation 1): `ReidentificationService.refuse` and `refuseApproval`, and the operator
  controller's `recordDenial`, swallow a failed denial audit, so the call is still refused and nothing is resolved. Follow-up for
  0.8 (owner decision): a denial whose audit write fails answers `AUDIT_UNAVAILABLE` and counts a metric, consistently across the
  re-identification, operator and tool paths.
- **Waiver 2 (owner).** The "no `sweep-seconds` reference" acceptance grep is waived (deviation 2): decision records may name the
  old key and no live reference remains.
- **Review suggestions.** The two docs suggestions were applied in the register (`design/contract-register.md`, the
  `hazelcast.lock.max.lease.time.seconds` row and D13; the S3 row now says the code is already in `docs/tools.md`). The two code
  suggestions are P3 follow-ups in `PLAN.md`: the mutable static logger in `ClusterRefusals` breaks under parallel tests, and the
  sentinel log test runs at `ClusterRefusals.refused` rather than at a real throw site.
