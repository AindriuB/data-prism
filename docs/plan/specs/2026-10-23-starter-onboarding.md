# Starter onboarding: make data-prism easy to adopt (design agreed 2026-10-23)

Owner goal: adopting data-prism should feel like adopting any Spring Boot starter.
Add the dependency, set a handful of properties, describe your API, and it works.
The audience is Java developers of any level. Success means a medium-level
developer gets one protected endpoint working from the docs in about 15 minutes,
and every startup refusal says which property to set.

Hard constraint: the privacy engine keeps failing closed (CLAUDE.md rule 2).
Unclassified data is refused. There is no local or development mode; the owner
rejected one explicitly.

## 1. Defaults and Spring-derived settings

- **Defaulted:**
  - `dataprism.metrics.sink`: Micrometer when it is on the classpath.
  - `privacy.profile`: `DEFAULT`.
  - `privacy.scope-lifetime`: a sensible value; the planner proposes it.
  - `identity.resolver`: `pass-through`, only when exactly one source is configured; with two or more sources and no resolver configured, startup refuses as before (amended 2026-10-24, OD-273-3 (a)).
  - `hazelcast.topology`: `single-node`.
  - `transport.mode`: `http`.
- **Derived from Spring:**
  - The JWT issuer, audience and JWKS come from `spring.security.oauth2.resourceserver.jwt.*`.
  - `audit.writer-id` comes from `spring.application.name`.
- **Auto-configured, back-off on user bean:**
  - the `SecurityFilterChain`, wired to the `JwtDecoder` and the MCP caller context;
  - the `PrivacyMetrics` bean.
- **Still required, with no default**, because each is a security decision:
  - the HMAC key reference;
  - the caller-claim mappings;
  - the security policy (purposes and role-to-capability mapping);
  - the audit sink;
  - case authorisation, from 0.8 (task 210).
- **Every default is visible.** The startup log and the docs list each defaulted or derived value, so nothing is silent.

## 2. Describing an API (owner choice "d")

- **Headline: YAML.**
  - The configured JSON source mode (connectors-rest, task 249) must work inside the embedded starter, as well as in the standalone server.
  - Field keys become **restricted JSONPath**, replacing flat or one-level names:
    - Allowed: `$`, child steps (`.name` and `['odd key']`), and `[*]` at any depth.
    - Refused at startup: recursive descent (`..`), filters, unions, indexes and slices; overlapping or ambiguous paths; a path that crosses a field already classified as a leaf.
    - Every value in a response must be covered by exactly one path. Anything uncovered is refused, as today (`UNKNOWN_FIELD`).
  - The same syntax is used for `subject-json-path` and for chainable link fields.
  - JSONPath only, with no nested-YAML alternative (owner decision).
  - Existing flat catalogues need a migration note, or must keep working.
- **Second route: Java, one annotation `@DataPrismSource`.**
  - **On an `@LlmExposedModel` record**, it means "GET this path". Example: `@DataPrismSource(name="customers", path="/customers/{subject}")`. The base URL and timeout stay in `dataprism.sources.<name>` YAML.
  - **On a Spring HTTP interface** (`@GetExchange`/`@PostExchange`, returning the record), it means "call through this interface". This covers POST lookups, headers and query parameters.
  - Either way, the adapter is generated through Spring's `RestClient`, and the developer writes no adapter class.
  - A hand-written `DataSourceAdapter` is documented as the advanced route.

## 3. Refusals

- Every startup refusal names the property to set, gives an example value, and links to the doc anchor.
- Today the failure analyser prints only the code and "see docs/configuration.md".
- Raw `IllegalArgumentException` refusals, such as a missing source timeout, become coded refusals.
- Refusal text never echoes secret values.

## 4. Docs and examples

- **New guide:** "Getting started with the Spring Boot starter", with a 15-minute target.
- **New template:** a copy-and-edit `application.yaml` that marks each setting required or optional.
- **`docs/configuration.md`:** split the Required column into true mandatory versus optional.
- **Stale notes to fix:** `protect-your-own-api.md:627-630` says `identity.resolver` is undocumented. Also document that the `PrivacyMetrics` bean is now auto-configured.
- **`examples/`** (owner choice "a"):
  - three small runnable apps: YAML-only; a record with `@DataPrismSource`; an HTTP interface with `@DataPrismSource`;
  - built and tested in CI on every PR against the reactor;
  - not published to Central.
- **After 0.8.0 is published:** a public template repository generated from the YAML example, depending only on Maven Central. It replaces the unpushed external demo project. (Note added 2026-10-26: the timing follows OD-283-1, which is still open; the planner recommends after 0.9.0, because the YAML example uses 0.9 features.)
- **Out of scope:** public-API catalogues. The owner wants them as separate associated repositories later, not as new modules here. Record this as a future idea only.

## 5. Fit with the release plan

- This is 0.9 work, after the 0.8 waves. It changes the starter's public contract, so it must land before the 0.9 freeze.
- It interacts with:
  - 210 (Spring wiring for case authorisation and the client allowlist);
  - 249 (configured JSON sources, description and chainable);
  - 213 (0.8 docs);
  - 270 and 271 (classified-record shapes, descriptor wiring);
  - the pending module-merge and pom-only-starter decision (PLAN.md:62).
- Order around those, and flag any owner decision explicitly.
- The Central file-count limit applies: no new published artifacts unless unavoidable, and examples are never published.
