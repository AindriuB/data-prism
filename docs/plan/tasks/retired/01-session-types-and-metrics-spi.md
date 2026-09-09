# 01 — Add the caller-session types, the metrics SPI and the security module skeleton

**Repo:** `.`
**Depends on:** none
**Owns:**
- pom.xml
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/InvestigationContext.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/Capability.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/PrivacyMetrics.java
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/Metric.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/InvestigationContextTest.java
- data-prism-core/src/test/java/io/github/aindriub/dataprism/core/PrivacyMetricsTest.java
- data-prism-security/pom.xml

## Goal
Everything else in this slice needs two vocabularies that do not exist yet: a type
naming *who* is asking, and a way to count things without ever labelling a counter
with a value. This task adds both to `core`, and registers an empty
`data-prism-security` module in the reactor so the tasks that fill it and depend on
it can be written against a resolvable coordinate.

Adding these as new files only, ahead of the tasks that consume them, is what lets
tasks 03, 04 and 05 run at the same time.

## Context
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/PrivacyContext.java:15-33 — the
  existing half of a session: scope, purpose, profile, expiry, pseudonymisation version. The new
  type is the other half and must not duplicate any of these fields
- docs/pack.md:1693-1717 — §51 `InvestigationContext`
- docs/pack.md:2739-2766 — §89 metric names and the banned label list
- docs/design-review.md:290-292 — §E adds `dataprism.identity.collision`,
  `dataprism.privacy.failclosed`, `dataprism.reidentification`
- data-prism-core/src/main/java/io/github/aindriub/dataprism/core/SecretKeyProvider.java:5-15 — the
  house style for an SPI with no vendor implementation in core
- pom.xml:31-33, 68-120 — module list and internal dependencyManagement
- docs/architecture.md — module table; `security` depends on `core` and nothing else

## Acceptance
- [ ] `InvestigationContext` is a record with components `principalId`, `clientId`, `caseId` and
      `Set<String> capabilities`. It carries no `scopeId`, `purpose` or `expiresAt`: those are already
      on `PrivacyContext`, which is the pseudonymisation key, and two sources of truth for the scope
      would be a defect. The javadoc states this as the deliberate deviation from §51.
- [ ] The compact constructor rejects a null or blank `principalId`, `clientId` or `caseId` with
      `IllegalArgumentException`, and copies `capabilities` immutably. There is no static factory,
      constant or default producing a caller named `system`, `anonymous` or equivalent — a grep for
      `"system"` in `data-prism-core/src/main/java` returns nothing new.
- [ ] `InvestigationContext` stores no raw claims map and no token, and exposes
      `boolean has(String capability)`.
- [ ] `Capability` is a final class of `public static final String` constants, private constructor,
      containing at least `EXPOSE_SOURCE_NAMES`, `GET_ENTITY_CONTEXT`, `COMPARE_ENTITY_SOURCES` and
      `DESCRIBE_ENTITY_MODEL`, plus `Set<String> KNOWN` holding exactly those values.
- [ ] `Metric` is an enum whose constants cover every name in §89 plus the three in §E, each carrying
      its dotted metric name; a test asserts the exact set of names, so adding one is a visible diff.
- [ ] `PrivacyMetrics` is an interface with `increment(Metric)`, `increment(Metric, String sourceName)`
      and `record(Metric, String sourceName, Duration)`, plus `static PrivacyMetrics none()` returning
      a no-op. There is no method accepting free-form tags or a value: the API makes a PII metric label
      unrepresentable rather than forbidden by convention. The javadoc states that `sourceName` may only
      be a configured source name, never anything read from a payload or a caller argument.
- [ ] `pom.xml` adds `<module>data-prism-security</module>` before `data-prism-orchestration`, and a
      `dependencyManagement` entry for `io.github.aindriub:data-prism-security` at `${project.version}`.
- [ ] `data-prism-security/pom.xml` exists, inherits the parent, declares artifactId
      `data-prism-security`, and depends on `data-prism-core`, `jackson-dataformat-yaml` and
      `slf4j-api`. It has no source directory yet; the empty-jar warning from the reactor is expected.
- [ ] `mvn -B verify` from the repo root passes with 213 or more tests (baseline 212 at `7db0491`,
      measured green on 2026-09-09).

## Out of scope
- Any edit to `PrivacyContext`. It gains no components in this slice.
- Any Micrometer dependency or implementation. `PrivacyMetrics` has one no-op implementation here;
  the Micrometer binding is task 07.
- Any source file under `data-prism-security/src/` — that is task 03.
- Any call site of the new types. Nothing consumes them in this task.
