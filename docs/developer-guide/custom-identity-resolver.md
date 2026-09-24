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

In production, only one of `IdentityResolver`'s two methods is ever called.
An MCP client's `subjectId` argument is treated as the canonical id directly
— `GetEntityContextTool` passes it straight into the `ContextRequest` it
builds (`GetEntityContextTool.java:147,179`) — and
`DefaultContextOrchestrator.requestsPerSource` hands it, unchanged, to
`expand` to find each source's own key:
`identities.expand(new IdentityResolver.CanonicalId(request.subjectId()), names)`
(`DefaultContextOrchestrator.java:313-314`). `resolve` — turning one source's
own key back into a canonical id — is part of the SPI
(`IdentityResolver.java:22-32`) and `MappedIdentityResolver` below implements
and tests it, but nothing in this codebase calls it today. Treat it as a
capability the interface documents, not as something a real request
exercises; if that changes, this page will say so.

The example this tutorial uses, `MappedIdentityResolver`, answers both
methods from a fixed table — a deterministic cross-reference, not a
computation:

```java
--8<-- "src/main/java/io/github/aindriub/dataprism/quickstart/extension/identity/MappedIdentityResolver.java:resolver"
```

Three things worth noticing:

- **`resolve` is exercised only by this page's own tests, today.** It never
  invents a canonical id from the key it was given (that is what
  `pass-through` does, below); it throws `IllegalArgumentException` for a key
  the table has no row for, rather than treating an unrecognised key as a
  new, unrelated subject. That is the SPI contract `resolve` promises,
  whichever caller ends up exercising it.
- **`expand` omits, it never pads.** A source absent from a subject's row is
  left out of the result, exactly as
  [`IdentityResolver`'s own contract](../extending.md#implement-identityresolver)
  requires — a source that has never billed a subject is never asked to. An
  id the table has no row for at all gets nothing: `expand` returns an empty
  list, not every source queried with the id unchanged (see `pass-through`,
  below, for what that alternative looks like and why it is risky).
- **Nothing here is probabilistic.** Two records with a similar name, or the
  same date of birth, are never treated as the same subject unless this exact
  table already says so.

`MappedIdentityResolverTest` proves all of this against the same table
(`ExampleIdentityMapping`, kept in its own class so the tutorial and the test
cite identical rows): one test resolves a key from each of the three sources
to the same canonical id, one calls `expand` for a subject `billing` has
never heard of and asserts that source is missing from the result, one calls
`expand` for a canonical id the table has no row for at all and asserts the
result is empty, one resolves an unknown key and asserts the
`IllegalArgumentException`, and one constructs a blank source name, key and
canonical id and asserts each is rejected — `SourceRef` and `CanonicalId`
(`data-prism-core`'s own records) refuse blank values themselves, before
`MappedIdentityResolver` ever sees them.

## Why the canonical id matters beyond the source lookup

`request.subjectId()` — the same canonical id `expand` is given — is not only
a lookup key. The same orchestrator run also uses it, unchanged, to:

- derive the per-scope pseudonym returned to the client in its place
  (`synthetics.syntheticValue(request.subjectId(), PrivacyNamespace.NONE, context)`,
  `DefaultContextOrchestrator.java:151`);
- compute the request's fingerprint
  (`fingerprinter.fingerprint(request.entityType() + "/" + request.subjectId(), context)`,
  `:152`);
- charge the scope's read budget
  (`budget.tryRead(context.scopeId(), request.subjectId(), limits.scopeReadBudget())`,
  `:165`).

Get the canonical id wrong and all three go wrong with it, not only the
source lookup: the wrong pseudonym is returned, correlation across calls for
what should be the same subject breaks, and the read budget is charged
against the wrong subject's history.

None of this hides the canonical id from anyone — the client is the one who
supplies it, as the `subjectId` argument. What never happens is the
*response* echoing it back raw: `ContextResponse.subject` is documented as
"the scope-local pseudonym, not the real identifier"
(`ContextResponse.java:16,48`), and it is that pseudonym — not
`request.subjectId()` — that a client ever sees in the reply
(`DefaultContextOrchestrator.java:222`).

## How `pass-through` differs, and when it is correct

The quickstart's own resolver is the opposite of the table above — under
`expand`, it queries every requested source with the caller's own id,
unchanged, never looking anything up:

```java
--8<-- "src/main/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartExtensionAutoConfiguration.java:quickstart-identity-resolver"
```

[`PassThroughIdentityResolver`](https://github.com/AindriuB/data-prism/blob/main/data-prism-core/src/main/java/io/github/aindriub/dataprism/core/PassThroughIdentityResolver.java)'s
`expand` returns one `SourceRef(sourceName, id.value())` per requested
source, for every source, with no lookup at all (`PassThroughIdentityResolver.java:21-25`).
That is the *correct* choice, not a shortcut, whenever every configured
source already agrees on one subject id — which is exactly the quickstart's
situation, and probably true of a good number of real deployments too.

It stops being correct once two sources disagree about what to call the same
subject, but the failure it produces is narrower than "wrong data merged
together": querying a source with an id that isn't really its key usually
just finds nothing — that source's adapter returns no record, and its
outcome is reported as `NO_DATA` (`SourceFanOut.java:138-139`). That is a
visible gap in `sources` only when at least one *other* configured source
answers for the same call. If every configured source comes back with
nothing, there is no gap to see: `merged` stays `null`
(`DefaultContextOrchestrator.java:193`) and the whole call is refused before
any response is built — see below. The case that actually merges the wrong
subject's data is narrower and easy to miss: a *different* subject who
genuinely has that same literal id as their own key at some source.
Pass-through has no way to tell the two apart, and the response would
silently include that other subject's data under this subject's pseudonym.
Nothing in the platform detects that condition for you; it looks exactly
like an `ANSWERED` source until someone reads the data.

This is also why a caller must ask a resolver like `MappedIdentityResolver`
for the *canonical id*, not a source-native key: asking it for `"C-1001"`
(the `customer` source's own key for `cust-001`, not a canonical id in its
table) finds no row, so `expand` returns an empty list. `SourceFanOut` then
skips every configured source entirely — none is ever called
(`SourceFanOut.java:80-82`) — so `merged` is never set and stays `null`.
`DefaultContextOrchestrator` treats that exactly like the all-sources-empty
case above: when `merged` is still `null` after the fan-out, it throws
`PrivacyRefusedException("NO_SOURCE_DATA", request.entityType(), "no source
returned a record for this subject")` — the path argument is the *client's
own* `entityType` from the request, not anything derived from the source
data (`DefaultContextOrchestrator.java:194`) — before building any
`ContextResponse` at all (`DefaultContextOrchestrator.java:193-195`).
`GetEntityContextTool`'s `catch (PrivacyRefusedException refused)` turns that
into the tool's error result, `"refused: " + refused.code() + " at " +
refused.path()` (`GetEntityContextTool.java:193`) — for this exception,
exactly `"refused: NO_SOURCE_DATA at CUSTOMER"`, for a request with
`entityType` `"CUSTOMER"` (this quickstart's one configured entity). That is
a refusal, not a visible-but-empty answer: there is no `sources` map and no
`ContextResponse` at all, only that error text.

## How to register a resolver

How you register a resolver depends on what you are building, and the two
are not interchangeable.

**An ordinary Spring Boot application** — one that depends on
`data-prism-spring-boot-starter` and component-scans its own code — registers
a resolver the same way it registers any other bean: a plain `@Configuration`
class in a package it already scans, with an ordinary `@Bean` method, no
special annotation required:

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

**A reviewed `-Dloader.path` extension jar** — like this very module, the one
the quickstart loads — is different: nothing on it is component-scanned, so a
plain `@Configuration` placed inside it would never run.
`ExampleIdentityResolverConfiguration` above is deliberately *not* written for
that case; it is not on this module's own
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
list, and `IdentityResolverOverrideTest` exercises it only as a plain user
configuration class supplied directly to `ApplicationContextRunner`, never as
part of the quickstart's own loaded jar. An extension jar instead needs its
own `@AutoConfiguration` class, registered the same way
`QuickstartExtensionAutoConfiguration` registers itself — one line in that
same `AutoConfiguration.imports` file, which [Write a data-source
adapter](write-an-adapter.md#3-wire-it-up-with-auto-configuration) shows for
the `DataSourceAdapter` case.

Being on that list is not, by itself, enough. Spring Boot gives no ordering
promise between two unrelated `@AutoConfiguration` classes just because both
are imported — so an extension's own, unconditional `IdentityResolver` bean
can just as easily be processed *after* whichever `@AutoConfiguration`
already supplies a default for this deployment, which is already registered
by then and does not step aside for a later, unconditional bean. The result
is two `IdentityResolver` beans in the same context, and startup fails
wherever something asks for exactly one —
`DataPrismAutoConfiguration.dataPrismContextOrchestrator` does
(`DataPrismAutoConfiguration.java:446`).

The general rule: order your own `@AutoConfiguration` *before* whichever
class supplies the `@ConditionalOnMissingBean` default in your deployment —
never rely on where either class happens to land unordered. For the
quickstart, that default-supplying class is `QuickstartExtensionAutoConfiguration`
itself. It is a different class if you instead rely on
`dataprism.identity.resolver: pass-through` (below) with no
`QuickstartExtensionAutoConfiguration` involved at all: that setting's
default comes from `DataPrismAutoConfiguration`'s own nested
`IdentityResolverSelection` (`@Import`-ed by it —
`DataPrismAutoConfiguration.java:128-135`), so the class to order before is
`DataPrismAutoConfiguration`, not `QuickstartExtensionAutoConfiguration`.
`ExampleOrderedIdentityResolverAutoConfiguration` names
`QuickstartExtensionAutoConfiguration` directly with
`@AutoConfiguration(before = QuickstartExtensionAutoConfiguration.class)`,
since this module already depends on it at compile time:

```java
--8<-- "src/main/java/io/github/aindriub/dataprism/quickstart/extension/identity/ExampleOrderedIdentityResolverAutoConfiguration.java:ordered-registration"
```

An extension that would rather not take a compile-time dependency just to
name a class this way can use `beforeName` instead, with the fully qualified
class name as a string — `@AutoConfiguration(beforeName =
"io.github.aindriub.dataprism.spring.boot.DataPrismAutoConfiguration")`, for
example, orders before `DataPrismAutoConfiguration` without importing its
type at all.

`IdentityResolverOrderingTest` proves both directions of this, feeding
`QuickstartExtensionAutoConfiguration` and the reader's own
`@AutoConfiguration` to `AutoConfigurations.of` with the quickstart's class
listed *first* in both cases, so nothing here depends on argument order:
with `before` declared, exactly one `IdentityResolver` bean exists and it is
the reader's; without it — `UnorderedIdentityResolverAutoConfiguration`, an
otherwise identical class with no `before` — the context fails to start with
`NoUniqueBeanDefinitionException`, "expected single matching bean but found
2: quickstartIdentityResolver,unorderedCustomIdentityResolver", exactly the
failure above. Both tests ask a stand-in bean for exactly one
`IdentityResolver`, the same shape as
`dataPrismContextOrchestrator`'s single `IdentityResolver` parameter — the
real `DataPrismAutoConfiguration` needs unrelated JWT, audit-sink and
Hazelcast-topology configuration this module does not own, so this is a
faithful minimal reproduction of the ambiguity, not a boot of production
wiring; see the test's own Javadoc.

Either way, `DataPrismAutoConfiguration.dataPrismIdentityResolverPreflight`
refuses to start with no `IdentityResolver` bean in the context at all, from
any source — registering one, whichever way, is not optional. A resolver can
also be supplied with no application code at all, by setting
`dataprism.identity.resolver: pass-through`
(`DataPrismAutoConfiguration.java:128-135`) — a separate, built-in
`PassThroughIdentityResolver` registration from `DataPrismAutoConfiguration`
itself, not from the quickstart's own `@AutoConfiguration` class. Pair that
setting with an extension's own unconditional `IdentityResolver` bean and the
same two-bean risk applies, but relative to `DataPrismAutoConfiguration`
rather than `QuickstartExtensionAutoConfiguration` — the general rule above
is what to follow, not this page's one worked example.

## Running the example's tests

```sh
mvn -pl data-prism-quickstart-extension -am test
```

```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running io.github.aindriub.dataprism.quickstart.extension.identity.IdentityResolverOrderingTest
19:02:16.263 [main] WARN org.springframework.context.annotation.AnnotationConfigApplicationContext -- Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'identityResolverConsumer' defined in io.github.aindriub.dataprism.quickstart.extension.identity.IdentityResolverOrderingTest$SingleIdentityResolverConsumer: Unsatisfied dependency expressed through method 'identityResolverConsumer' parameter 0: No qualifying bean of type 'io.github.aindriub.dataprism.core.IdentityResolver' available: expected single matching bean but found 2: quickstartIdentityResolver,unorderedCustomIdentityResolver
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.644 s -- in io.github.aindriub.dataprism.quickstart.extension.identity.IdentityResolverOrderingTest
[INFO] Running io.github.aindriub.dataprism.quickstart.extension.identity.IdentityResolverOverrideTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.099 s -- in io.github.aindriub.dataprism.quickstart.extension.identity.IdentityResolverOverrideTest
[INFO] Running io.github.aindriub.dataprism.quickstart.extension.identity.MappedIdentityResolverTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.022 s -- in io.github.aindriub.dataprism.quickstart.extension.identity.MappedIdentityResolverTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
```

The `WARN` line above is expected, not a failure: it is Spring logging the
context-startup exception `anUnorderedAutoConfigurationCanProduceTwoBeansAndFailToStart`
deliberately triggers, inside a test that then asserts the context failed to
start — the two `IdentityResolverOrderingTest` tests both pass.

Where each extension point plugs in, relative to the privacy engine, is drawn
out on the [developer guide overview](index.md#the-extension-points).
