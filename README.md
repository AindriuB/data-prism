# Data Prism

<!-- mcp-name: io.github.AindriuB/data-prism -->

A privacy layer between MCP clients and enterprise APIs.

**Status: the walking skeleton and every slice through S9a are built**, with 19
Maven submodules (`pom.xml:24-42`; 20 Maven projects in the reactor counting
the root `pom`-packaged aggregator itself) and a passing test suite. The
privacy engine, correlation and consistency findings, parallel mTLS
connectors, embedded Hazelcast identity cache and read budget, an OAuth2
resource server with session-derived `PrivacyContext`, audit and metrics are
all real and exercised end to end. The standalone server is the primary
deployment surface; the Spring Boot starter is the embedded option. A
one-command local Compose quickstart also exists: see "Try it" below.
Not built: the re-identification operator surface (deferred past V1 by
decision, see `docs/architecture.md#decisions-worth-knowing`), the
Elasticsearch connector and its search tools, and the append-only audit sink
with hash-chain verifier (a file/SLF4J sink exists; the append-only sink is
deliberately deferred). Two MCP tools ship today, `get_entity_context` and
`compare_entity_sources` — the other two named in the design review,
`search_entity_data` and `describe_entity_model`, are not yet built. See
`docs/plan/PLAN.md` for what is open.

## The problem

An organisation wants an LLM to investigate live business data spread across
several systems. Giving the model direct API access is not acceptable: those APIs
carry personal and confidential data, each system represents the same entity
differently, and raw identifiers let anything downstream correlate across
sessions.

The obvious fix — redact everything sensitive — destroys the investigation. Once
three systems' names for one person are all `[REDACTED]`, the model cannot tell
whether it is looking at one person or three.

## What Data Prism does

It sits between the two and does two things that are easy to confuse:

**It makes identity consistent.** One subject gets one synthetic identity across
every source, derived deterministically from `(scope, subject, namespace,
algorithm version, key)` — never random, never stored in plaintext, and
reproducible without the cache. The same person in three systems reads as one
person to the model.

**It leaves the data inconsistent, and says so.** If those three systems disagree
about a name, the answer carries a finding that says they disagree. The platform
never makes enterprise data look cleaner than it is. That distinction is the
point of the project:

> Identity representation becomes consistent. Underlying data inconsistencies
> become *more* visible, not less.

Pseudonyms are scoped. The same person in two different investigations gets two
different synthetic identities, so nothing correlates across cases by accident.

## What it is not

Not an API gateway, not an ETL platform, not a master-data system, not an
identity provider, and not an entity-resolution engine — correlation requires a
key the sources already share, behind a documented SPI. It carries no business
domain: no `Customer`, `Taxpayer` or `Employee` type exists outside the example
application.

**It is not anonymisation.** Under GDPR Art. 4(5), pseudonymised data is still
personal data. Sending Data Prism output to a third-party model is still
processing, and still needs a lawful basis, a DPIA, and a transfer mechanism
where the provider is outside the EU. The platform reduces exposure; it does not
remove the obligation.

## Try it

The fastest way to see a real MCP call answered by the real privacy engine —
no local JDK, no Maven install, one command:

```bash
docker compose up --build
```

brings up the standalone server, a synthetic fixture API and a local HTTPS
JWT issuer, and proves an agent-compatible `get_entity_context` call returns
a pseudonymised response. Walk through it in
[`docs/quickstart.md`](docs/quickstart.md); connect your own agent client to
either that stack or a real deployment via
[`docs/agents/`](docs/agents/README.md).

## If you found this on the MCP registry

The `ghcr.io/aindriub/data-prism-server` image listed there is published as a
multi-architecture manifest list covering `linux/amd64` and `linux/arm64`,
each built and verified natively — `docker run` on Apple Silicon or any other
arm64 host pulls the arm64 image directly, no emulation required.

It is not a one-command install, on either architecture. `docker run` alone
yields a server that refuses to start: `DataPrismContractValidator` demands a
reviewed `DataSourceAdapter` bean for every configured source, and
`DataPrismProperties.validate()` demands a full deployment configuration (JWT
issuer/audience/JWKS, caller-claim mappings, security policy, HMAC key
reference, audit sink, metrics sink, Hazelcast topology). Neither ships in the
image. Two things an operator must supply themselves before it serves
anything:

- **A reviewed `DataSourceAdapter` (and `IdentityResolver`) jar** for each
  API you are protecting, mounted onto the image's loader path.
- **A deployment configuration** satisfying the `dataprism.*` vocabulary.

[`docs/configuration.md`](docs/configuration.md) is the authoritative,
complete contract for both. The "Try it" section above is a local Compose
fixture for evaluation, not this image or that configuration.

## Documentation

| | |
|---|---|
| `docs/quickstart.md` | One-command local Compose demonstration — start here |
| `docs/agents/` | Connecting an MCP agent client, local fixture or authenticated remote |
| `docs/tools.md` | What each shipped MCP tool takes and returns, worked examples |
| `docs/extending.md` | Protecting a new source: a reviewed Java adapter, or the configuration-driven JSON REST mode |
| `docs/architecture.md` | Module map, dependency rules, the boundaries that must not be crossed, dated decisions |
| `docs/design-review.md` | Amendments to the specification, with reasoning. **Authoritative** |
| `docs/development-plan.md` | Slice order, sizing, and the decisions that block the first one |
| `docs/pack.md` | The original specification. Historical; superseded where the review disagrees |
| `docs/conventions.md` | Code style and the privacy rules a diff must satisfy |
| `docs/workflow.md` | How work is split and run |
| `docs/plan/PLAN.md` | What is open, in priority order |
| `docs/plan/HISTORY-INDEX.md` | What was built, and what it cost to find out |

`docs/plan/PLAN.md` is the working queue. GitHub Issues is the front door for
anything coming from outside — file there, not in `PLAN.md`.

## Stack

Java 21, Spring Boot 3.x, Maven multi-module, Hazelcast, Model Context Protocol
via the official MCP Java SDK.

Artifacts publish under group `io.github.aindriub` as `data-prism-<module>`, with
package root `io.github.aindriub.dataprism`.

## Building and running

Requires Java 21 (the build compiles with `--release 21`, so a newer local JDK
is fine) and Maven >= 3.6.3 (`pom.xml:201-203` enforces this).

```bash
mvn -B --no-transfer-progress verify
```

This is the same command CI runs (`.github/workflows/build.yml`). It builds all
19 submodules plus the root aggregator, runs the full test suite, the
ArchUnit boundary rules, and the enforcer rule that keeps the classpath on a
single Jackson major.

`data-prism-server` is the primary executable distribution. Its `/health`
liveness probe is public and carries no deployment detail; its configured MCP
path (normally `/mcp`) requires a verified bearer JWT. It deliberately contains
no source schema, fixture adapter, or key. Supply configuration described in
[`docs/configuration.md`](docs/configuration.md), plus a reviewed adapter for
each configured source. Two ways to get one: a Java adapter extension with an
annotated response model (the general case — nested objects, any transport),
or, when the source's response is one flat JSON object, the published
`data-prism-connectors-rest` artefact — loaded via `-Dloader.path`, configured
entirely in YAML, no Java required. See
[`docs/extending.md`](docs/extending.md) for both paths and exactly where the
configuration-driven one's coverage ends (it never descends into a nested
object).

Adapter extensions are ordinary jars containing Spring Boot auto-configuration
that declares the required `DataSourceAdapter` beans and an explicit reviewed
`IdentityResolver` (use `PassThroughIdentityResolver` only when every source
genuinely shares the same identifier). Register that configuration in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
then load reviewed extension jars without rebuilding the server:

```bash
LOADER_PATH=/opt/data-prism/extensions \
  java -jar data-prism-server/target/data-prism-server-0.2.0.jar \
  --spring.config.additional-location=file:/etc/data-prism/application.yaml
```

The process refuses startup if configuration, secrets, operational bindings, or
the exact configured adapter set is missing. `data-prism-integration-tests` is
the reactor's cross-module integration test suite, not a fixture-only demo,
and is never packaged into a deployable artefact. Container packaging and
Compose orchestration for a real, locally runnable instance of this exist too
— see "Try it" above and `docs/quickstart.md`.

## Contributing

See `CONTRIBUTING.md`. The short version: read `docs/conventions.md` before
opening a pull request, and expect the privacy rules in it to be enforced
literally.

## Licence

Apache License 2.0 — see `LICENSE` and `NOTICE`.
