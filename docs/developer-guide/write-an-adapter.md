---
title: "Tutorial: write a data-source adapter"
description: A step-by-step tutorial for writing a Data Prism data-source adapter, from an empty module to a running MCP tool call.
---

# Write a data-source adapter

This tutorial goes from an empty module to a pseudonymised MCP tool call,
using the same seven steps a real integration takes. Every code, YAML, pom
and Dockerfile block below is pulled at build time from a marked region in
`data-prism-quickstart-extension` or `docker/` — a real module in this
repository that Maven compiles and `QuickstartSmokeIT` exercises end to end
on every build — never hand-copied. If a region or file ever went missing or
was renamed, the site build would fail, not silently show stale code.

This is the shorter, guided walk. [`docs/extending.md`](../extending.md) is
the full reference for everything below, including the parts this tutorial
only links to rather than repeats. If your API is flat or nests JSON at most
one level deep, [`docs/protect-your-own-api.md`](../protect-your-own-api.md)
gets you the same result with no Java at all.

## 1. Model the response and classify its fields

A `DataSourceAdapter` returns one Java type. Every field on a type exposed
through MCP must carry a classification annotation — `@LlmExposedModel`
requires one of `@InternalIdentifier`, `@SensitiveData`, `@NonSensitive` or
`@SubjectIdentifier` on every field, and the annotation processor fails the
build if one is missing:

```java
--8<-- "src/main/java/io/github/aindriub/dataprism/quickstart/extension/CustomerModel.java:model"
```

`@InternalIdentifier` marks the record's own correlation id; `@SensitiveData`
classifies a field and *suggests* what should happen to it (here, synthesizing
`customerName` and redacting `email`) — the privacy engine's own profile
rule, or the stricter of the two if both apply, has the final say;
`@NonSensitive` asserts a field is safe to emit unchanged and requires a
`reason()`. Full reference:
[Classify the model with `@LlmExposedModel`](../extending.md#classify-the-model-with-llmexposedmodel),
including what the processor rejects and why.

## 2. Implement `DataSourceAdapter`

The adapter itself is small: name the source, state the response type, fetch
one record.

```java
--8<-- "src/main/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartCustomerAdapter.java:adapter"
```

Two details worth carrying into your own adapter even though nothing enforces
them: the subject id is a URI *template variable* (`"/customers/{id}"`), so
`RestClient` encodes it rather than letting it steer the path; and a 404 is
treated as data, not failure — returning `null` lets the orchestrator record
this source's outcome as `NO_DATA` instead of tripping its circuit breaker.
Full reference: [Implement `DataSourceAdapter`](../extending.md#implement-datasourceadapter).

## 3. Wire it up with auto-configuration

Nothing on a `-Dloader.path` jar is component-scanned, so Spring Boot has to
be told which `@AutoConfiguration` class to load. That class supplies the
`DataSourceAdapter` bean the platform refuses to start without, plus a
fallback `IdentityResolver` bean — one is only mandatory because
`DataPrismAutoConfiguration.dataPrismIdentityResolverPreflight` refuses to
start without some `IdentityResolver` bean, which `dataprism.identity.resolver:
pass-through` or an application-supplied bean can equally satisfy:

```java
--8<-- "src/main/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartExtensionAutoConfiguration.java:autoconfiguration"
```

`quickstartIdentityResolver()` returns the shipped `PassThroughIdentityResolver`
— correct here because the quickstart's one source already keys on the
subject id it was asked for; tutorial 2 covers writing your own. Registration
is one line, in one file:

```
--8<-- "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports:import"
```

Full reference: [Register the extension](../extending.md#register-the-extension).

## 4. Shape the pom

Inside this repository, the dependencies are unversioned because the reactor
parent pins them, and all four are scoped `provided` (test-scope entries
omitted below):

```xml
--8<-- "pom.xml:dependencies"
```

**This is not a standalone pom.** Outside this reactor, every dependency
above needs an explicit `<version>` element, and the annotation processor
below needs a real version rather than `${project.version}` — see
[The pom shape](../extending.md#the-pom-shape) for the version-complete
equivalent that stands alone, verified by building it against a clean local
repository. The annotation processor itself goes on the processor path, never
as a `<dependency>`:

```xml
--8<-- "pom.xml:processor-plugin"
```

## 5. Load the extension

The packaged server adds a `-Dloader.path` jar's own classes and resources to
its classpath — never its dependencies, which is why the pom above marks
every dependency `provided`. Read
[The `-Dloader.path` trap](../extending.md#the-dloaderpath-trap) before
relying on this. The quickstart's own server image loads this module's jar
exactly that way:

```
--8<-- "server/Dockerfile:entrypoint"
```

An operator deploying the distribution image uses the same mechanism, but as
a directory of jars rather than a single one — `LOADER_PATH`, read by Spring
Boot's `PropertiesLauncher`:

```
--8<-- "distribution/Dockerfile:loader-path"
```

Full reference: [Load the extension](../extending.md#load-the-extension).

## 6. Configure the source

`QuickstartExtensionAutoConfiguration`'s `@Value` bindings above read
`dataprism.sources.customer.*`.
The quickstart's own deployment configuration sets it:

```yaml
--8<-- "server/application.yaml:customer-source"
```

Binding is by the adapter's own `sourceName()` return value, not by bean or
class name — configure a name no adapter returns, and the server refuses to
start with `UNRESOLVED_SOURCE_ADAPTER`. Full reference:
[Bind `dataprism.sources.<name>` to your adapter](../extending.md#bind-dataprismsourcesname-to-your-adapter).

## 7. Run it and see the pseudonymised response

`docker compose up` pulls published images, which do not contain the code
above unless it has already been released. To actually exercise what this
page shows, build from source instead:

```sh
docker compose -f compose.yaml -f compose.build.yaml up --build
```

This builds and starts four services: a throwaway certificate generator, the
local token issuer, the synthetic customer fixture API, and the standalone
server with this module's jar loaded through the `-Dloader.path` entrypoint
above. The first run takes a few minutes — a Maven reactor build inside three
of the four images — later runs are fast. The server's own startup does not
wait on the issuer or the fixture API being fully ready, so if the very first
command below fails, wait a few seconds and retry it; see
[`docs/quickstart.md`](../quickstart.md) for the full walkthrough of this same
stack, including what each service is and how they trust each other.

Once it settles, mint a token and call `get_entity_context` — the same
handshake [`docs/quickstart.md`](../quickstart.md) walks through in full —
using the runnable script this repository ships. The `up --build` above runs
in the foreground and blocks its terminal, so run this in a second one:

```sh
examples/quickstart-demo/run.sh
```

Real output, from an actual run, 2026-09-24:

```
PASS: get_entity_context for CUSTOMER 1001 returned a pseudonymised response.

  field         real fixture value                     pseudonymised response
  ------------  -------------------------------------  --------------------------
  subjectId     1001                                   SUBJ-KNSYWNZ9
  customerName  Fixture Person One                     Rowan Okafor (D1B5CR19)
  email         fixture.person.one@example.invalid     [REDACTED]
```

`customerName` is a stable synthetic name — the module's own code, from step
1, suggested `SYNTHESIZE`, and the deployment's privacy profile agreed — never
the fixture's own `Fixture Person One`. `email` is redacted outright: the
model suggested `REDACT`, and the privacy engine, which always has the final
say, did not relax it. Neither
is anonymisation in any strict, re-identification-proof sense: pseudonymised
output is still personal data, recoverable by whoever holds the deployment's
HMAC key, and this tutorial does not claim otherwise. `status`, classified
`@NonSensitive` in step 1, passes through unchanged (not shown in the table
above, but present in the raw MCP response) — a decision the model states
explicitly, never a platform default: an unclassified field would have
refused the whole response, not disclosed it (see
[the developer guide overview](index.md) for where `AuditSink` and the
classification annotations are covered in more depth).

When you are done:

```sh
docker compose down -v
```
