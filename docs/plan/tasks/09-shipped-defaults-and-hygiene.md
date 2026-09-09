# 09 — Make the shipped defaults the ones the tests assert, and clear two pieces of debt

**Repo:** `.`
**Depends on:** 07
**Owns:**
- data-prism-example/pom.xml
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/DataPrismAssembly.java
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/WorkedExampleTest.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/EndToEndTest.java
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/ShippedDefaultsTest.java
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ScopeResolver.java

Nothing under `data-prism-example/src/**/java/**/example/http/**`, and not
`ArchitectureTest.java`, `src/main/resources/**` or `src/test/resources/**` —
all of those are task 07's.

**Ownership note.** `data-prism-example/pom.xml` is also in task 07's `Owns`.
That is why this task depends on 07 rather than running beside it: 09 starts
from a tree in which 07's pom edits are already merged, and adds one dependency
element to them. Do not start 09 before 07 has merged.
`ExampleApplication.java` is in 07's `Owns` too, for the one item where 07 derives
stdio's `productionDeployment` flag from the active Spring profile — same reason,
same rule: 09 starts from the merged file, keeps that wiring, and re-checks the
line numbers cited below against it.

## Goal
Two of these four are the same defect this project keeps producing: a control
that exists, is tested, and is not what actually ships. `DataPrismAssembly`
mints an investigation context holding `EXPOSE_SOURCE_NAMES` by construction, so
the documented worked example prints real source names while the application
path masks them; and the shipped `developer` role's capability set is asserted
on by nothing, because every capability test builds its own policy. Both are
fixed the same way — make the shipped default the safe one, and put a test on it
that fails if someone widens it again.

The other two are ordinary debt: a stale class javadoc, and a dependency used
directly but declared transitively. They are here because they are small, they
touch files this task already owns, and two implementers in a row have been told
to leave the javadoc alone because they did not own the module.

## Context
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/DataPrismAssembly.java:103-109 —
  the constructor comment says `EXPOSE_SOURCE_NAMES` is there "because this example's
  sources are fictional and its output is meant to be read"; task 06 superseded that
  reasoning in `ExampleApplication` and could not reach this file
- data-prism-example/src/main/java/io/github/aindriub/dataprism/example/ExampleApplication.java:31-34,49-50 —
  the scoped-down caller task 06 built, and the class javadoc that already claims
  "no `EXPOSE_SOURCE_NAMES`". The claim is currently true, and nothing keeps it true
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/WorkedExampleTest.java:104,142 —
  the two assertions that name `customer-api`, `account-api` and `order-api` directly
- data-prism-example/src/test/java/io/github/aindriub/dataprism/example/EndToEndTest.java:245 —
  the only other caller of `investigationContext()`; there is no production caller
- data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceAliasing.java:40-45
  and DefaultContextOrchestrator.java:230-233 — aliasing is applied both to the `sources()`
  keys and to the names correlation puts into findings, so both change together
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/ScopeResolver.java:12-22
  and :40-47 — the stale class javadoc, and the `@throws` on `resolve` that already
  documents `UNKNOWN_PURPOSE` correctly
- data-prism-security/src/main/java/io/github/aindriub/dataprism/security/SecurityPolicy.java:44-52 —
  `capabilitiesFor(Set<String> roles)`, which is what a test on the shipped policy asserts through
- pom.xml:110-114 — `data-prism-security` is already version-managed in the parent
- docs/conventions.md:111-113 and :250-262 — the mutation rule, and the reviewer-isolation
  rule that goes with it
- docs/pack.md:2126-2160 — §64, the worked example this task keeps honest

## Decision to implement, not to revisit
`investigationContext()` has no production caller: `ExampleApplication` stopped
using it in task 06, and the only remaining callers are `WorkedExampleTest` and
`EndToEndTest:245`. So the fix goes at the factory, not at the test — the
assembly stops minting a context that carries `EXPOSE_SOURCE_NAMES`, and the
worked example is updated to show what the application shows. Do not instead
build a scoped-down caller inside `WorkedExampleTest` and leave the factory as
it is: a factory that hands an unmasking context to anyone who asks is the same
hazard one level up.

## Acceptance
- [ ] No method on `DataPrismAssembly` returns an `InvestigationContext` holding
      `Capability.EXPOSE_SOURCE_NAMES`. `ShippedDefaultsTest` asserts that
      `DataPrismAssembly.standard().investigationContext()` has a capability set of
      exactly `{GET_ENTITY_CONTEXT}`, and that `has(Capability.EXPOSE_SOURCE_NAMES)` is
      `false`. The superseded comment at DataPrismAssembly.java:103-106 is replaced by one
      stating the current reason rather than the old one.
- [ ] `WorkedExampleTest` passes against the masked default: the assertions at :104 and
      :142 assert the scope-local aliases rather than `customer-api`, `account-api` and
      `order-api`, and a further assertion states that none of those three real names
      appears in `response.sources()`, in `response.findings()`, or in the serialised
      entity. The alias assertions cannot pass on an empty or absent collection — assert
      exactly three distinct aliases, and assert that the alias for a given source is the
      same string in `sources()` as in the finding that names it.
- [ ] `ExampleApplication`'s shipped `SecurityPolicy` is reachable from a test — a named
      static factory rather than a local built inside `main` — and `main` uses that same
      factory, so the shipped default has one definition and not two.
- [ ] `ShippedDefaultsTest` asserts that `capabilitiesFor(Set.of("developer"))` on that
      shipped policy equals exactly `Set.of(Capability.GET_ENTITY_CONTEXT)`, and separately
      that it does not contain `Capability.EXPOSE_SOURCE_NAMES`. The test is proved able to
      fail: add `EXPOSE_SOURCE_NAMES` to the shipped role, record the failing assertion
      message in the close-out, revert. Per the reviewer-isolation convention, note in the
      close-out that this is a mutation-shaped proof, so a reviewer must read from
      `git show` rather than the working tree while it runs.
- [ ] `ScopeResolver`'s class javadoc gains one or two sentences saying that `resolve`
      refuses a caller whose purpose is not in the configured list, failing closed with
      `UNKNOWN_PURPOSE`. Nothing else in that file changes — no signature, no behaviour, no
      reformatting of untouched lines. The diff for this item is comment-only.
- [ ] `data-prism-example/pom.xml` declares `io.github.aindriub:data-prism-security`
      directly, with no `<version>` element, alongside the dependencies task 07 added.
      `mvn -B dependency:tree -pl data-prism-example` shows it at depth one.
- [ ] `mvn -B verify` passes from the repo root with no test failures and no skipped
      tests, and the test count is not below the tip of `main` at the time 09 starts —
      establish that number by running the suite on `main` after 07 merges and before
      making any change here. It was 300 at 07's base.

## Out of scope
- Anything under `data-prism-example/.../example/http/**`, `ArchitectureTest`,
  `application.yaml`, or the HTTP application's own `SecurityPolicy`. That policy is task
  07's and is loaded from yaml; this task governs only the stdio development default
  built in `ExampleApplication`.
- Removing or renaming `investigationContext()`, and any change to `InvestigationContext`,
  `Capability` or `SourceAliasing` in the core and orchestration modules. Narrow the
  default the example mints; do not reshape the type system around it.
- Any other javadoc in `data-prism-security`, and any behaviour change in `ScopeResolver`.
  Item four is a comment fix and stops there.
- Granting `EXPOSE_SOURCE_NAMES` anywhere as a configurable example role. If the worked
  example turns out to need real source names to remain readable, that is a finding to
  report, not a grant to make.
