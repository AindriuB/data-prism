# Data Prism

<!-- mcp-name: io.github.AindriuB/data-prism -->

[![Build](https://github.com/AindriuB/data-prism/actions/workflows/build.yml/badge.svg)](https://github.com/AindriuB/data-prism/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.aindriub/data-prism-spring-boot-starter.svg)](https://central.sonatype.com/artifact/io.github.aindriub/data-prism-spring-boot-starter)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

<!-- site-intro:start -->
Fail-closed privacy layer that pseudonymises enterprise API data for LLM agents and MCP clients.

Data Prism is an open-source privacy layer for Java/Spring teams putting LLM agents or MCP clients in front of internal APIs holding customer data. It pseudonymises personal data per privacy scope, refuses anything unclassified, and can keep a hash-chained audit trail.

**Who it's for.** Java/Spring platform and backend teams putting LLM agents
or MCP clients in front of internal APIs that hold customer data. If nothing
you run exposes personal data to a model, you don't need this.

**Status: the walking skeleton and every slice through S9a are built**, with 18
Maven submodules (19 Maven projects in the reactor counting the root
`pom`-packaged aggregator itself) and a passing test suite. The privacy
engine, correlation and consistency findings, parallel mTLS connectors,
embedded Hazelcast identity cache and read budget, an OAuth2 resource server
with session-derived `PrivacyContext`, audit and metrics are all real and
exercised end to end. The standalone server is the primary deployment
surface; the Spring Boot starter is the embedded option. A one-command local
Compose quickstart also exists: see "Try it" below. Two MCP tools ship
today, `get_entity_context` and `compare_entity_sources` — the other two
named in the design review, `search_entity_data` and
`describe_entity_model`, are not yet built (`docs/tools.md` "Not yet
built"). A durable, append-only, hash-chained audit sink and an offline
`AuditChainVerifier` ship as of 0.3.0, opt-in via
`dataprism.audit.sink: hash-chained`; the verifier catches an edit or
deletion inside a writer's chain, but cannot detect truncation of a writer's
most recent records or the deletion of a whole process boot's records, and
the trail does not resist an operator, or anyone else, who already has
write access to the file (`docs/audit.md` "What this does and does not
prove"). Not built: the re-identification operator surface (deferred past
V1 by decision, see `docs/architecture.md#decisions-worth-knowing`) and the
Elasticsearch connector and its search tools. See `docs/plan/PLAN.md` for
what is open.

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
docker compose up
```

pulls the published `ghcr.io/aindriub/data-prism-quickstart-<name>` images
(pin one with `QUICKSTART_IMAGE_TAG=0.3.0`; run
`docker compose -f compose.yaml -f compose.build.yaml up --build` instead to
build every image from source) and brings up the standalone server, a
synthetic fixture API and a local HTTPS JWT issuer, proving an
agent-compatible `get_entity_context` call returns a pseudonymised response.
Walk through it in
[`docs/quickstart.md`](docs/quickstart.md); connect your own agent client to
either that stack or a real deployment via
[`docs/agents/`](docs/agents/README.md).

Once you have seen the demo, protect your own API: `docs/quickstart.md` ends
with a "What next" section pointing at
[`docs/protect-your-own-api.md`](docs/protect-your-own-api.md), a YAML-only
walkthrough from a real JSON REST API to a working `get_entity_context` call.
<!-- site-intro:end -->

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

The full set of user docs is also published, rendered and searchable, at
<https://aindriub.github.io/data-prism/>.

### User docs

Each row links the site page and the repo file it is built from.

| Doc | Site | What it covers |
|---|---|---|
| [`docs/quickstart.md`](docs/quickstart.md) | [quickstart/](https://aindriub.github.io/data-prism/quickstart/) | One-command local Compose demonstration — start here |
| [`docs/protect-your-own-api.md`](docs/protect-your-own-api.md) | [protect-your-own-api/](https://aindriub.github.io/data-prism/protect-your-own-api/) | Pointing Data Prism at your own API instead of the fixture |
| [`docs/configuration.md`](docs/configuration.md) | [configuration/](https://aindriub.github.io/data-prism/configuration/) | The authoritative, complete `dataprism.*` deployment configuration contract |
| [`docs/tools.md`](docs/tools.md) | [tools/](https://aindriub.github.io/data-prism/tools/) | What each shipped MCP tool takes and returns, worked examples |
| [`docs/extending.md`](docs/extending.md) | [extending/](https://aindriub.github.io/data-prism/extending/) | Protecting a new source: a reviewed Java adapter, or the configuration-driven JSON REST mode |
| [`docs/audit.md`](docs/audit.md) | [audit/](https://aindriub.github.io/data-prism/audit/) | What the hash-chained audit trail records, and how to verify it |
| [`docs/architecture.md`](docs/architecture.md) | [architecture/](https://aindriub.github.io/data-prism/architecture/) | Module map, dependency rules, the boundaries that must not be crossed, dated decisions |
| [`docs/agents/`](docs/agents/README.md) | [agents/](https://aindriub.github.io/data-prism/agents/) | Connecting an MCP agent client, local fixture or authenticated remote |
| [`docs/agents/stdio.md`](docs/agents/stdio.md) | [agents/stdio/](https://aindriub.github.io/data-prism/agents/stdio/) | The local stdio fixture workflow: a real MCP tool call with no JWT, network call or source system |
| [`docs/agents/remote-http.md`](docs/agents/remote-http.md) | [agents/remote-http/](https://aindriub.github.io/data-prism/agents/remote-http/) | The authenticated Streamable HTTP workflow against a real MCP endpoint; no development bypass |
| [`docs/faq.md`](docs/faq.md) | [faq/](https://aindriub.github.io/data-prism/faq/) | Direct answers on pseudonymisation, PII detection, Java requirements, the audit trail and prompt injection |
| [`docs/comparison.md`](docs/comparison.md) | [comparison/](https://aindriub.github.io/data-prism/comparison/) | How Data Prism compares to Presidio, LLM Guard, NeMo Guardrails and MCP gateways or proxies |
| [`docs/use-cases/pseudonymise-customer-data-spring-boot.md`](docs/use-cases/pseudonymise-customer-data-spring-boot.md) | [use-cases/pseudonymise-customer-data-spring-boot/](https://aindriub.github.io/data-prism/use-cases/pseudonymise-customer-data-spring-boot/) | Pseudonymising customer data from a Spring Boot API before an LLM agent sees it |
| [`docs/use-cases/gdpr-data-minimisation-mcp.md`](docs/use-cases/gdpr-data-minimisation-mcp.md) | [use-cases/gdpr-data-minimisation-mcp/](https://aindriub.github.io/data-prism/use-cases/gdpr-data-minimisation-mcp/) | GDPR data minimisation for MCP tools |
| [`docs/use-cases/consistent-pseudonyms-across-systems.md`](docs/use-cases/consistent-pseudonyms-across-systems.md) | [use-cases/consistent-pseudonyms-across-systems/](https://aindriub.github.io/data-prism/use-cases/consistent-pseudonyms-across-systems/) | Keeping one customer recognisable across systems without exposing identity |
| [`CHANGELOG.md`](CHANGELOG.md) | [changelog/](https://aindriub.github.io/data-prism/changelog/) | Every notable Data Prism change by version, in Keep a Changelog format |

### Internal / project working docs

Not published on the site.

| Doc | What it covers |
|---|---|
| [`docs/design-review.md`](docs/design-review.md) | Amendments to the specification, with reasoning. **Authoritative** |
| [`docs/development-plan.md`](docs/development-plan.md) | Slice order, sizing, and the decisions that block the first one |
| [`docs/pack.md`](docs/pack.md) | The original specification. Superseded and historical; describes tools that were never built |
| [`docs/conventions.md`](docs/conventions.md) | Code style and the privacy rules a diff must satisfy |
| [`docs/workflow.md`](docs/workflow.md) | How work is split and run |
| [`docs/plan/PLAN.md`](docs/plan/PLAN.md) | What is open, in priority order |
| [`docs/plan/HISTORY-INDEX.md`](docs/plan/HISTORY-INDEX.md) | What was built, and what it cost to find out |

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
18 submodules plus the root aggregator, runs the full test suite, the
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
  java -jar data-prism-server/target/data-prism-server-0.3.0.jar \
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
