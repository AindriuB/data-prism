---
title: "Tutorial: write a custom identity resolver"
description: How IdentityResolver relates a subject's per-source keys, when pass-through is wrong, and how to register your own.
---

# Write a custom identity resolver

`PassThroughIdentityResolver` — the resolver
[Write a data-source adapter](write-an-adapter.md) wires up — works only
because the quickstart's one source already keys on the exact subject id a
caller supplies. Real integrations are rarely that tidy: a customer system, a
billing system and a CRM can all hold the same person under three different
keys. `IdentityResolver` is the extension point for that case. This tutorial
does not repeat [`docs/extending.md`'s "Implement
`IdentityResolver`"](../extending.md#implement-identityresolver), the full
reference; it walks through one small, compiled, unit-tested example instead.

Every Java block below is pulled at build time from a marked region in
`data-prism-quickstart-extension` — never hand-copied. That module also
carries the example's tests; the mvn run near the end of this page is quoted
from a real run of them.

## How one subject is recognised across sources

`IdentityResolver` has two methods: `resolve` turns one source's own key into
the platform's canonical id for that subject; `expand` goes the other way,
turning a canonical id into the keys to fetch from a set of sources. The
example this tutorial uses, `MappedIdentityResolver`, answers both from a
fixed table — a deterministic cross-reference, not a computation:

```java
--8<-- "src/main/java/io/github/aindriub/dataprism/quickstart/extension/identity/MappedIdentityResolver.java:resolver"
```

Three things worth noticing:

- **The table is the only source of truth.** `resolve` never invents a
  canonical id from the key it was given (that is what `pass-through` does,
  below); it throws `IllegalArgumentException` for a key the table has no row
  for, rather than treating an unrecognised key as a new, unrelated subject —
  a typo or a source the table has not caught up with fails loudly instead of
  silently fragmenting one subject's history across two canonical ids.
- **`expand` omits, it never pads.** A source absent from a subject's row is
  left out of the result, exactly as
  [`IdentityResolver`'s own contract](../extending.md#implement-identityresolver)
  requires — a source that has never billed a subject is never asked to.
- **Nothing here is probabilistic.** Two records with a similar name, or the
  same date of birth, are never treated as the same subject unless this exact
  table already says so. A canonical id is also never exposed to a client —
  see `IdentityResolver.CanonicalId`'s own Javadoc — it exists only to look up
  each source's own key.

`MappedIdentityResolverTest` proves all of this against the same table
(`ExampleIdentityMapping`, kept in its own class so the tutorial and the test
cite identical rows): one test resolves a key from each of the three sources
to the same canonical id, one calls `expand` for a subject `billing` has never
heard of and asserts that source is missing from the result, one resolves an
unknown key and asserts the `IllegalArgumentException`, and one constructs a
blank source name, key and canonical id and asserts each is rejected —
`SourceRef` and `CanonicalId` (`data-prism-core`'s own records) refuse blank
values themselves, before `MappedIdentityResolver` ever sees them.

## How `pass-through` differs, and when it is correct

The quickstart's own resolver is the opposite of the table above — it does no
lookup at all:

```java
--8<-- "src/main/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartExtensionAutoConfiguration.java:quickstart-identity-resolver"
```

[`PassThroughIdentityResolver`](https://github.com/AindriuB/data-prism/blob/main/data-prism-core/src/main/java/io/github/aindriub/dataprism/core/PassThroughIdentityResolver.java)
treats a source's own key as the canonical id, unchanged. That is the *correct*
choice, not a shortcut, whenever every configured source already agrees on
one subject id — which is exactly the quickstart's situation, and probably
true of a good number of real deployments too. It becomes the wrong choice
the moment two sources disagree about what to call the same subject: `resolve`
would then mint two different canonical ids for one person, one per source,
and every field the orchestrator merges across sources would silently
correlate to the wrong "subject" for at least one of them. Nothing in the
platform detects that condition for you; it looks exactly like two
`ANSWERED` sources until someone reads the data.

## How to register a resolver

Registration is a plain `@Bean`, in an application's own configuration —
nothing special, and no annotation of its own needed on the bean method:

```java
--8<-- "src/main/java/io/github/aindriub/dataprism/quickstart/extension/identity/ExampleIdentityResolverConfiguration.java:registration"
```

This works because `quickstartIdentityResolver()` above carries
`@ConditionalOnMissingBean`: an application-supplied `IdentityResolver` bean
is always found first, and Spring Boot's own auto-configuration ordering never
lets the quickstart's fallback get registered as well. `IdentityResolverOverrideTest`
proves both directions of that claim with `ApplicationContextRunner`: with no
other bean present, `QuickstartExtensionAutoConfiguration` supplies the
`PassThroughIdentityResolver`; with `ExampleIdentityResolverConfiguration` also
in the context, the resolved `IdentityResolver` bean is the
`MappedIdentityResolver` above instead — never both, and never neither.

`DataPrismAutoConfiguration.dataPrismIdentityResolverPreflight` refuses to
start with no `IdentityResolver` bean in the context at all, from any source —
registering one, whichever way, is not optional.

## Running the example's tests

```sh
mvn -pl data-prism-quickstart-extension -am test
```

```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running io.github.aindriub.dataprism.quickstart.extension.identity.IdentityResolverOverrideTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.677 s -- in io.github.aindriub.dataprism.quickstart.extension.identity.IdentityResolverOverrideTest
[INFO] Running io.github.aindriub.dataprism.quickstart.extension.identity.MappedIdentityResolverTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.021 s -- in io.github.aindriub.dataprism.quickstart.extension.identity.MappedIdentityResolverTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

Where each extension point plugs in, relative to the privacy engine, is drawn
out on the [developer guide overview](index.md#the-extension-points).
