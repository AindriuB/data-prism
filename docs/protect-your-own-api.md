# Protect your own API: a YAML-only walkthrough

You have a JSON REST API whose response is either flat or nests objects at
most one level deep, and you want Data Prism to sit in front of it and answer
MCP callers with a pseudonymised view instead of your raw data. This
walkthrough takes you from that API to a working `get_entity_context` call,
writing only YAML. No Java class, no `pom.xml`, no `META-INF` registration
step appears anywhere below.

That is `data-prism-connectors-rest`'s configuration-driven JSON REST mode: a
published artifact
(`io.github.aindriub:data-prism-connectors-rest`), loaded into a running
`data-prism-server` the same way any other reviewed adapter is —
`-Dloader.path` — but configured entirely by a `json-sources:` catalogue
instead of compiled Java. It has one real limit, stated here so you can check
it against your own API before going further: its field resolver descends one
level into a named nested sub-catalogue — a root field declared `nested:
<name>` — but no further; a nested catalogue's own leaves cannot themselves
declare `nested:` or `identifier: true`, so two levels of nesting, or an
identifier inside a nested catalogue, refuses at load time rather than
silently flattening or dropping data. There is also no dotted path or
JSONPath anywhere in this grammar: every field name, at either level, and
`subject-json-path` itself, is a single bare, exact-match property name. If
your response nests objects two levels or more, needs custom fetch logic
beyond a single templated `GET`, or needs a model this catalogue cannot
express, stop here and read [`docs/extending.md`](extending.md) instead;
nothing below lifts that limit. The section "A nested response" below walks
the one-level case this mode does cover.

This walkthrough does not restate the full `dataprism.*` configuration
vocabulary — that is [`docs/configuration.md`](configuration.md) — or the two
MCP tools an agent calls — that is [`docs/tools.md`](tools.md). It is the
YAML-only counterpart to [`docs/extending.md`](extending.md): the same
destination, without writing code.

## What you need

- A running `data-prism-server` distribution and the
  `data-prism-connectors-rest` jar alongside it. This walkthrough runs both
  from this repository's own `mvn package` output
  (`data-prism-server/target/data-prism-server-0.3.1.jar` and
  `data-prism-connectors-rest/target/data-prism-connectors-rest-0.3.1.jar`).
  `data-prism-server` is never published to Maven Central at any version
  (`data-prism-server/pom.xml` sets `skipPublishing`); its distribution
  always comes from a from-source build like this one, a GitHub Release, or
  the GHCR image, never from Central. `data-prism-connectors-rest` is on
  Central, but only at `0.3.0` — 0.3.1 was not published there — and its own
  source is unchanged since that release (`git diff v0.3.0..HEAD --
  data-prism-connectors-rest/src/main` is empty), so the jar this walkthrough
  builds is code-identical to the `0.3.0` artifact Central serves under
  `io.github.aindriub:data-prism-connectors-rest`.
  "Build the jars, then start the two fixtures" below gives the exact build
  command; every path in this walkthrough is relative to the repository
  root, and every command below is run from there.
- Your own flat JSON REST API. This walkthrough stands in
  `data-prism-quickstart-fixtures` for it — a real HTTPS service in this
  repository, built by the command below, with a `/customers/{id}` endpoint and two
  synthetic records, never a real person's data (see its own
  `CustomerController`). Swap its address in the catalogue below for your
  real API's, and its `fields:` for your real response's shape; nothing else
  about the steps changes.
- A JWT issuer your own deployment trusts. This walkthrough mints tokens from
  `data-prism-quickstart-issuer`, a local fixture issuer this repository also
  ships. A real deployment points `dataprism.security.jwt.issuer` and
  `dataprism.security.jwt.jwk-set-uri` at its own identity provider instead —
  see `docs/configuration.md`; nothing about the JSON REST connector changes
  that.

## Build the jars, then start the two fixtures

Everything below is run from the repository root. Build the jars for the
server, the connector, and the two fixtures this walkthrough stands in your
own API and identity provider for, once:

```sh
mvn -q -DskipTests package
```

Both fixtures speak TLS only — the standalone server refuses a plaintext
`http://` source `base-url` and a plaintext `http://` JWKS location outright
(task 21), fixture or not — so this walkthrough needs one throwaway,
self-signed keystore and a matching truststore before either process starts.
Like `QuickstartSmokeIT`'s `@TempDir` and `docker/certs-init/generate-certs.sh`'s
Docker volume, this key material is generated outside the repository working
tree, so it is never at risk of being committed. It also has to survive the
fixtures and server below running in three separate terminals, so it lives
at a fixed, re-derivable path under `$HOME` rather than a one-off `mktemp -d`
that only the terminal which created it would know: every command below that
touches this key material restates the same line first, so a fresh terminal
that has not seen any earlier command still resolves `$DP_WALKTHROUGH_CERT_DIR` to the same
place.

```sh
DP_WALKTHROUGH_CERT_DIR=$HOME/data-prism-walkthrough-certs
mkdir -p "$DP_WALKTHROUGH_CERT_DIR"
```

These are the same three `keytool` invocations `QuickstartSmokeIT`
(`data-prism-quickstart-extension`) and `docker/certs-init/generate-certs.sh`
both run, adapted to loopback-only use here:

```sh
DP_WALKTHROUGH_CERT_DIR=$HOME/data-prism-walkthrough-certs
mkdir -p "$DP_WALKTHROUGH_CERT_DIR"

keytool -genkeypair -alias walkthrough -keyalg RSA -keysize 2048 -validity 2 \
  -keystore "$DP_WALKTHROUGH_CERT_DIR/walkthrough.p12" -storetype PKCS12 \
  -storepass walkthrough-demo-only -keypass walkthrough-demo-only \
  -dname "CN=data-prism-walkthrough" \
  -ext "san=ip:127.0.0.1,dns:localhost"

keytool -exportcert -alias walkthrough -keystore "$DP_WALKTHROUGH_CERT_DIR/walkthrough.p12" \
  -storetype PKCS12 -storepass walkthrough-demo-only -file "$DP_WALKTHROUGH_CERT_DIR/walkthrough.cer"

keytool -importcert -alias walkthrough -file "$DP_WALKTHROUGH_CERT_DIR/walkthrough.cer" \
  -keystore "$DP_WALKTHROUGH_CERT_DIR/walkthrough-trust.p12" -storetype PKCS12 \
  -storepass walkthrough-demo-only -noprompt
```

If `$DP_WALKTHROUGH_CERT_DIR` already holds a keystore from an earlier run of
this walkthrough, skip the `keytool` block above — reusing the existing
material is fine — or delete the directory first to start fresh; re-running
it against an alias that already exists fails with `keytool error:
java.lang.Exception: Key pair not generated, alias walkthrough already exists`.

`$DP_WALKTHROUGH_CERT_DIR/walkthrough.p12` is the keystore the two fixtures serve HTTPS from;
`$DP_WALKTHROUGH_CERT_DIR/walkthrough-trust.p12` is the truststore the server trusts it with
below. Neither is committed or reused anywhere else — remove the whole
directory (`rm -rf "$DP_WALKTHROUGH_CERT_DIR"`) once you are done with this walkthrough.

Start `data-prism-quickstart-fixtures` — the flat JSON REST API this
walkthrough stands in for your own — on port 8543:

```sh
DP_WALKTHROUGH_CERT_DIR=$HOME/data-prism-walkthrough-certs

java -jar data-prism-quickstart-fixtures/target/data-prism-quickstart-fixtures-0.3.1.jar \
  --server.port=8543 \
  --server.ssl.key-store="file:$DP_WALKTHROUGH_CERT_DIR/walkthrough.p12" \
  --server.ssl.key-store-password=walkthrough-demo-only \
  --server.ssl.key-store-type=PKCS12 \
  --server.ssl.key-alias=walkthrough
```

```sh
curl -sk https://127.0.0.1:8543/health
```

```json
{"status":"UP"}
```

Start `data-prism-quickstart-issuer` — the JWT issuer this walkthrough mints
tokens from — on port 8544, in a second terminal. This terminal has not run
any earlier command in this walkthrough, so restate `DP_WALKTHROUGH_CERT_DIR` before using it:

```sh
DP_WALKTHROUGH_CERT_DIR=$HOME/data-prism-walkthrough-certs

java -jar data-prism-quickstart-issuer/target/data-prism-quickstart-issuer-0.3.1.jar \
  --server.port=8544 \
  --server.ssl.key-store="file:$DP_WALKTHROUGH_CERT_DIR/walkthrough.p12" \
  --server.ssl.key-store-password=walkthrough-demo-only \
  --server.ssl.key-store-type=PKCS12 \
  --server.ssl.key-alias=walkthrough \
  --quickstart.issuer.issuer-id=https://issuer.walkthrough.invalid \
  --quickstart.issuer.audience=data-prism-walkthrough
```

```sh
curl -sk https://127.0.0.1:8544/health
```

```json
{"status":"UP"}
```

Leave both running for the rest of this walkthrough.

## The catalogue

[`examples/json-sources/customer-api.yaml`](../examples/json-sources/customer-api.yaml)
is the exact file this walkthrough runs, committed alongside this document
with every key commented in place:

```yaml
json-sources:
  customer-api:
    base-url: https://127.0.0.1:8543
    path: /customers/{subject}
    timeout: PT5S
    model-version: customer-v1
    subject-json-path: customerId
    fields:
      customerId:
        identifier: true
      customerName:
        classifications: [PII]
        namespace: PERSON_NAME
        action: SYNTHESIZE
      email:
        classifications: [CONTACT]
        namespace: EMAIL
        action: REDACT
      status:
        nonSensitive: "enumerated lifecycle state"
```

`base-url`, `path` and `timeout` are this source's transport, stated here and
nowhere else — `dataprism.sources.customer-api` never has to repeat it (task
54 removed that duplication; the one remaining `dataprism.sources` entry the
older duplication needed is simply absent below, and the server still
starts). `model-version` is a tag you commit to, not a runtime schema check.
`subject-json-path` names the response field carrying the correlation id, and
must point at a `fields:` entry marked `identifier: true`. `fields:` is the
allowlist: every property the response may ever carry, including the subject
field, named with exactly one of `identifier: true`, `nonSensitive: <reason>`,
or a `classifications`/`namespace`/`action` triple. A property present in a
response but absent here is refused before it reaches the scrubbing engine —
demonstrated below, not just asserted.

## Load the connector — no Java, no `META-INF`

`-Dloader.path` adds the connector jar's own classes to the running server's
classpath, the same mechanism any reviewed adapter extension uses (see
`docs/extending.md`'s own trap section on this). The catalogue's location is
the one property this mode reads:

```sh
-Dloader.path=data-prism-connectors-rest/target/data-prism-connectors-rest-0.3.1.jar
-Ddataprism.json-sources.config-location=file:examples/json-sources/customer-api.yaml
```

That is the entire extension mechanism for this mode: one jar on the loader
path, one property naming a YAML file. There is no auto-configuration class
to register, because `data-prism-connectors-rest` already ships and registers
its own (`ConfiguredJsonSourcesAutoConfiguration`,
`ConfiguredJsonSourcesInitializer`) — that registration belongs to the
connector's own jar, not to anything you write.

## The identity resolver — one property, opt-in

Data Prism refuses to start with no `IdentityResolver` bean present; a
configuration-driven source is not exempt from that. `dataprism.identity.resolver`
selects the built-in default from configuration, so a deployment where every
source genuinely keys its records on the same subject id needs no Java for
this either:

```
--dataprism.identity.resolver=pass-through
```

This is an explicit opt-in, not a default: omit it and startup refuses with
`MISSING_IDENTITY_RESOLVER`. Name anything other than `pass-through` and it
refuses with `UNSUPPORTED_IDENTITY_RESOLVER` rather than falling back
silently. Both are proven below, not just asserted. `pass-through` means the
source's own record key — here, `customerId`, the field `subject-json-path`
names above — *is* the canonical subject id, used as-is, with no lookup or
matching step in between. If your sources disagree about identity —
different customer numbers across systems, a probabilistic match — this
property cannot express that; you still need a real `IdentityResolver` and
the Java path in `docs/extending.md`.

**PROOF: omitting the property refuses startup.** The same server command as
below (see "Run it"), with `--dataprism.identity.resolver=pass-through`
deleted and nothing else changed:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Data Prism refused to start: a required piece of deployment configuration is missing, invalid, or unsafe.

Refusal: DataPrismConfigurationException: MISSING_IDENTITY_RESOLVER

Action:

Supply the configuration this refusal names. See the deployment contract in docs/configuration.md for what `MISSING_IDENTITY_RESOLVER` requires, and docs/quickstart.md for a runnable, fully-configured demo to compare against.
```

**PROOF: an unrecognised value refuses startup.** The same command with
`--dataprism.identity.resolver=probabilistic-match` instead:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Data Prism refused to start: a required piece of deployment configuration is missing, invalid, or unsafe.

Refusal: DataPrismConfigurationException: UNSUPPORTED_IDENTITY_RESOLVER

Action:

Supply the configuration this refusal names. See the deployment contract in docs/configuration.md for what `UNSUPPORTED_IDENTITY_RESOLVER` requires, and docs/quickstart.md for a runnable, fully-configured demo to compare against.
```

## Run it

The rest of the command line is the same security, privacy, audit, metrics
and Hazelcast configuration any protected deployment needs — see
`docs/configuration.md` for that vocabulary in full; none of it is specific
to this connector. Trust for the server's own outbound calls (the fixture
API, JWKS discovery) is set the same way `docs/quickstart.md` sets it, via
the JVM's own trust store system properties. Both fixtures above are
foreground processes occupying their own terminals, so run the server itself
in a third terminal, restating `DP_WALKTHROUGH_CERT_DIR` again:

```sh
DP_WALKTHROUGH_CERT_DIR=$HOME/data-prism-walkthrough-certs

export DATAPRISM_WALKTHROUGH_HMAC_KEY=walkthrough-demo-hmac-key-material-32-bytes-plus

java \
  -Djavax.net.ssl.trustStore="$DP_WALKTHROUGH_CERT_DIR/walkthrough-trust.p12" \
  -Djavax.net.ssl.trustStorePassword=walkthrough-demo-only \
  -Djavax.net.ssl.trustStoreType=PKCS12 \
  -Dloader.path=data-prism-connectors-rest/target/data-prism-connectors-rest-0.3.1.jar \
  -Ddataprism.json-sources.config-location=file:examples/json-sources/customer-api.yaml \
  -jar data-prism-server/target/data-prism-server-0.3.1.jar \
  --server.port=8080 \
  --dataprism.identity.resolver=pass-through \
  --dataprism.security.jwt.issuer=https://issuer.walkthrough.invalid \
  --dataprism.security.jwt.audience=data-prism-walkthrough \
  --dataprism.security.jwt.jwk-set-uri=https://127.0.0.1:8544/jwks \
  --dataprism.security.caller-claims.principal=sub \
  --dataprism.security.caller-claims.roles=roles \
  --dataprism.security.caller-claims.investigation=case_id \
  --dataprism.security-policy.purposes[0]=investigation \
  --dataprism.security-policy.roles.investigator[0]=GET_ENTITY_CONTEXT \
  --dataprism.privacy.profile=DEFAULT \
  --dataprism.privacy.scope-lifetime=8h \
  --dataprism.privacy.hmac-key.key-id=walkthrough-v1 \
  --dataprism.privacy.hmac-key.environment-variable=DATAPRISM_WALKTHROUGH_HMAC_KEY \
  --dataprism.audit.sink=slf4j \
  --dataprism.audit.writer-id=walkthrough \
  --dataprism.metrics.sink=micrometer \
  --dataprism.hazelcast.topology=single-node
```

```
Tomcat started on port 8080 (http) with context path '/'
Started DataPrismServerApplication in 3.63 seconds (process running for 4.105)
```

```sh
curl -s http://127.0.0.1:8080/health
```

```json
{"status":"UP"}
```

## Get a token and call it

Minting a token and driving the MCP Streamable HTTP handshake is unrelated
to this connector — the same three curl calls `docs/quickstart.md` walks
through by hand:

```sh
TOKEN=$(curl -sk -X POST https://127.0.0.1:8544/token \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')

SESSION=$(curl -sD - -o /dev/null -X POST http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"protect-your-own-api-walkthrough","version":"1.0.0"}}}' \
  | grep -i '^Mcp-Session-Id:' | tr -d '\r' | cut -d' ' -f2)

curl -s -X POST http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $TOKEN" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

curl -s -X POST http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer $TOKEN" \
  -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_entity_context","arguments":{"entityType":"CUSTOMER","subjectId":"1001"}}}'
```

The fixture API's own record for subject `1001` (see its `CustomerController`)
has real name `Fixture Person One` and real email
`fixture.person.one@example.invalid`. The `tools/call` curl above sends
`Accept: text/event-stream`, so what actually came back is SSE-framed, not
bare JSON — one `id:`/`event:`/`data:` frame, the JSON-RPC response inside
`data:`:

```
id: 903eface-c8a4-4845-9631-aaa8fea1dc5d
event: message
data: {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"entityType\":\"CUSTOMER\",\"subject\":\"SUBJ-VHK4SXCQ\",\"sources\":{\"ORGANISATION_IDENTITY-6BE1NJ46\":\"ANSWERED\"},\"findings\":[],\"entity\":{\"customerName\":\"Casey Okafor (G2C8D3R4)\",\"email\":\"[REDACTED]\",\"status\":\"ACTIVE\"}}"}],"isError":false,"structuredContent":{"entityType":"CUSTOMER","subject":"SUBJ-VHK4SXCQ","sources":{"ORGANISATION_IDENTITY-6BE1NJ46":"ANSWERED"},"findings":[],"entity":{"customerName":"Casey Okafor (G2C8D3R4)","email":"[REDACTED]","status":"ACTIVE"}}}}
```

Neither raw value appears. `customerName` is a stable synthetic substitute in
the `PERSON_NAME` namespace (the catalogue's own `namespace:` above), `email`
is redacted outright per its `action: REDACT`, and `status` passes through
unchanged because the catalogue marked it `nonSensitive`. The subject itself
(`SUBJ-VHK4SXCQ`) is a pseudonym too, not `1001`. This ran with
`-Dloader.path=data-prism-connectors-rest/target/data-prism-connectors-rest-0.3.0.jar`
(recorded against 0.3.0) alone — no second jar,
no custom extension, no `IdentityResolver` bean compiled anywhere — and
against the catalogue exactly as it is committed in
`examples/json-sources/customer-api.yaml`, comments included: the transport
this response actually dialled is stated in that one file, once.

## Prove it fails closed

An incomplete catalogue — the same file with `timeout:` deleted — refuses at
startup, before any traffic reaches the source, naming the offending source
and the missing key. This is a catalogue variant, not key material, so it
gets its own scratch directory rather than sharing `$DP_WALKTHROUGH_CERT_DIR`; the same
re-derivable-path shape applies, so it also survives a fresh terminal:

```sh
DP_WALKTHROUGH_SCRATCH_DIR=$HOME/data-prism-walkthrough-scratch
mkdir -p "$DP_WALKTHROUGH_SCRATCH_DIR"

sed '/timeout: PT5S/d' examples/json-sources/customer-api.yaml \
  > "$DP_WALKTHROUGH_SCRATCH_DIR/customer-api-no-timeout.yaml"
```

Start the server with the same command as "Run it". This is again its own
terminal, so restate both variables it needs first: `DP_WALKTHROUGH_CERT_DIR`
for the keystore/truststore, and `DP_WALKTHROUGH_SCRATCH_DIR` for the
catalogue variant the `sed` command above just produced:

```sh
DP_WALKTHROUGH_CERT_DIR=$HOME/data-prism-walkthrough-certs
DP_WALKTHROUGH_SCRATCH_DIR=$HOME/data-prism-walkthrough-scratch
```

with
`-Ddataprism.json-sources.config-location=file:examples/json-sources/customer-api.yaml`
replaced by
`-Ddataprism.json-sources.config-location=file:$DP_WALKTHROUGH_SCRATCH_DIR/customer-api-no-timeout.yaml`
and nothing else changed:

```
java.lang.IllegalArgumentException: json source customer-api has no timeout;
this mode requires an explicit bounded timeout, it does not default one
```

A response carrying a property the catalogue never named is refused before
it reaches the caller, the same `UNKNOWN_FIELD` refusal a Java-first model's
own undeclared field gets — proven directly against this mechanism (not
reproduced here) in
`data-prism-connectors-rest/src/test/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSourceEndToEndTest.java`,
`unknownFieldFailsClosed`.

## A nested response

[`examples/json-sources/customer-api-nested.yaml`](../examples/json-sources/customer-api-nested.yaml)
is `customer-api.yaml`'s sibling: the same source, plus one field, `address`,
declared `nested: address` and pointed at a `nested-catalogues:` entry of its
own:

```yaml
    fields:
      customerId:
        identifier: true
      customerName:
        classifications: [PII]
        namespace: PERSON_NAME
        action: SYNTHESIZE
      email:
        classifications: [CONTACT]
        namespace: EMAIL
        action: REDACT
      status:
        nonSensitive: "enumerated lifecycle state"
      address:
        nested: address
    nested-catalogues:
      address:
        line1:
          nonSensitive: "street address line, reviewed as inert structure"
        postcode:
          classifications: [PII]
          namespace: ADDRESS
          action: SYNTHESIZE
```

Nesting goes exactly one level: `address` carries no identifier of its own —
it inherits its subject from the enclosing record — so its own leaves may be
`nonSensitive` or classified only; `identifier: true` and a further `nested:`
are both refused when the catalogue loads, not silently flattened. This is
loaded and scrubbed below by the real engine, not asserted in prose:
[`examples/json-sources/NestedCatalogueWalkthrough.java`](../examples/json-sources/NestedCatalogueWalkthrough.java)
is a complete, runnable program, `public` but living in
`io.github.aindriub.dataprism.connectors.rest` (the exact package
`ConfiguredJsonNestedCatalogueScrubbingTest` in that module is in, because
`ConfiguredJsonPayload` is package-private), calling the same public
`ConfiguredJsonSources.fromYaml` this connector uses to read every
`json-sources:` catalogue, and the same `ConfiguredJsonScrubbingEngine` that
test drives directly. Its own header comment gives the exact `javac`/`java`
invocation; in short, `install` first — `package` alone leaves
`data-prism-pseudonymisation` and `data-prism-orchestration` (imported here
transitively) unresolved from this reactor, so `mvn dependency:build-classpath`
fails to resolve them — no `0.3.1` artifact of either exists on Maven Central
to fall back to. The `-am` flag
installs every upstream module this one depends on, not just those two
(`mvn -q install -DskipTests -pl data-prism-connectors-rest -am`) — note this
installs this branch's build at `0.3.1`, alongside, not overwriting, any
`0.3.0` artifacts already sitting in the reader's local `~/.m2` repository
from Maven Central. Then
compile and run this one file against `data-prism-core`'s and
`data-prism-connectors-rest`'s `target/classes` plus
`data-prism-connectors-rest`'s Maven dependency classpath (`mvn -q
dependency:build-classpath`) — no jar, no elided wiring: every line that runs
is in that file.

Run as written, it loads `customer-api-with-address` from
`customer-api-nested.yaml` and prints its resolved catalogue:

```
sources: [customer-api-with-address]
root fields: [address, customerId, customerName, email, status]
nested catalogues: [address]
  address.line1 -> classifications=[] namespace=NONE action=null nonSensitiveReason=street address line, reviewed as inert structure
  address.postcode -> classifications=[PII] namespace=ADDRESS action=SYNTHESIZE nonSensitiveReason=null
```

then scrubs the fixture-shaped response
`{"customerId":"1001","customerName":"Fixture Person One","email":"fixture.person.one@example.invalid","status":"ACTIVE","address":{"line1":"123 Main St","postcode":"90210"}}`
against the `DEFAULT` profile:

```json
{"customerName":"Rowan Walsh (4MZ4CCK9)","email":"[REDACTED]","status":"ACTIVE","address":{"line1":"123 Main St","postcode":"48 Orchard Mews, Belmont (V8338JMM)"}}
```

`address.line1` passes through unchanged (`nonSensitive`), `address.postcode`
is synthesised in the `ADDRESS` namespace (task 71's discriminator, the same
eight-character Crockford base32 tag every other synthetic value in this
walkthrough now carries) exactly like a root-level `SYNTHESIZE` field, and
neither raw value (`90210`, `Fixture Person One`) appears anywhere in the
result.

**PROOF: the deeper-than-declared refusal.** `address` declares `postcode` a
scalar/classified leaf. A response where that property arrives as a structure
instead — a stale catalogue against a wire shape that changed — refuses as
`NESTED_LEAF_NOT_SCALAR`, distinct from both `UNCLASSIFIED_STRUCTURE` and
`UNKNOWN_FIELD`, before anything is scrubbed. The same program's second
scrub call, against that stale-shaped body, prints:

```
REFUSED: NESTED_LEAF_NOT_SCALAR at customer-api-with-address$.address.postcode: nested catalogue leaf field is declared scalar/classified but the response carries a structure there; the catalogue is stale against the wire shape
```

No raw value, and nothing from inside the unexpected structure, appears in
that message — only the catalogue-declared path `$.address.postcode` does,
and that path segment happens to match the wire's own field name here because
nothing renames it. The mirror-image failure — a scalar arriving where
`nested:` itself is declared — is `NESTED_FIELD_NOT_STRUCTURED`, and a
property inside the nested object that its own catalogue never named is
refused as `UNKNOWN_FIELD`, the identical code the root catalogue's own
undeclared fields get. `ConfiguredJsonNestedCatalogueScrubbingTest` and
`ConfiguredJsonNestedHttpTest` (`data-prism-integration-tests`, the latter
against the real MCP HTTP/SSE transport) both drive every one of these codes
directly.

## Close-out

Every code fence above was executed, not transcribed:

| What it shows | Command that produced it |
|---|---|
| The two fixtures' health checks | `curl -sk https://127.0.0.1:8543/health` and `curl -sk https://127.0.0.1:8544/health`, against processes started with the `java -jar data-prism-quickstart-fixtures/...`/`data-prism-quickstart-issuer/...` commands in "Build the jars, then start the two fixtures" |
| Server starts, no `dataprism.sources.customer-api` entry anywhere on the command line | `java -Dloader.path=data-prism-connectors-rest/target/data-prism-connectors-rest-0.3.0.jar -Ddataprism.json-sources.config-location=file:examples/json-sources/customer-api.yaml -jar data-prism-server/target/data-prism-server-0.3.0.jar ...` (recorded against 0.3.0; server log, "Started DataPrismServerApplication") |
| `GET /health` | `curl -s http://127.0.0.1:8080/health` |
| `MISSING_IDENTITY_RESOLVER` | the same server command with `--dataprism.identity.resolver=pass-through` removed |
| `UNSUPPORTED_IDENTITY_RESOLVER` | the same server command with `--dataprism.identity.resolver=probabilistic-match` |
| The pseudonymised `get_entity_context` response, SSE frame included | the `initialize` / `notifications/initialized` / `tools/call` sequence in "Get a token and call it", run against a token freshly minted by `curl -sk -X POST https://127.0.0.1:8544/token` |
| The missing-`timeout` refusal | the same server command, config-location pointed at the `sed`-produced `$DP_WALKTHROUGH_SCRATCH_DIR/customer-api-no-timeout.yaml` |
| The nested catalogue's resolved fields, the scrubbed nested response, and the `NESTED_LEAF_NOT_SCALAR` refusal in "A nested response" | one run of `NestedCatalogueWalkthrough.java`, built against `data-prism-connectors-rest`'s own `target/classes` plus `mvn -q dependency:build-classpath` (after `mvn -q install -DskipTests -pl data-prism-connectors-rest -am`), against `examples/json-sources/customer-api-nested.yaml`; this one run prints all three outputs shown above, in order |
| *(no captured output)* | `mvn -q -DskipTests package` and the three `keytool` commands in "Build the jars, then start the two fixtures" ran, but produce nothing worth capturing — a quiet build and key material respectively, not output that documents behaviour |

`$DP_WALKTHROUGH_CERT_DIR/walkthrough.p12`/`$DP_WALKTHROUGH_CERT_DIR/walkthrough-trust.p12` above are a
throwaway self-signed keystore and truststore generated with the
`keytool -genkeypair`/`-exportcert`/`-importcert` commands in "Build the jars,
then start the two fixtures", inside `$HOME/data-prism-walkthrough-certs`, a
directory outside this repository's working tree — the same three
invocations `QuickstartSmokeIT` (`data-prism-quickstart-extension`) and
`docker/certs-init/generate-certs.sh` both run, kept out of the tree the same
way those two precedents do — never committed, never reused. Its path is
fixed rather than a fresh `mktemp -d` each time precisely so that the second
and third terminals this walkthrough uses can restate `$DP_WALKTHROUGH_CERT_DIR` and resolve
to the same directory without inheriting it from the terminal that created
it. `$DP_WALKTHROUGH_SCRATCH_DIR/customer-api-no-timeout.yaml` in "Prove it fails closed" is
a catalogue variant, not key material, so it lives in its own directory,
`$HOME/data-prism-walkthrough-scratch`, for the same reason.

```sh
DP_WALKTHROUGH_CERT_DIR=$HOME/data-prism-walkthrough-certs
DP_WALKTHROUGH_SCRATCH_DIR=$HOME/data-prism-walkthrough-scratch
rm -rf "$DP_WALKTHROUGH_CERT_DIR" "$DP_WALKTHROUGH_SCRATCH_DIR"
```

removes everything either directory holds once you are done with this
walkthrough. `data-prism-quickstart-fixtures` and `data-prism-quickstart-issuer`
were started as plain `java -jar` processes on `127.0.0.1`, standing in for
your own API and your own identity provider respectively; nothing about the
JSON REST connector itself depends on either being a Data Prism fixture
rather than your real infrastructure.

One defect surfaced while preparing this walkthrough, filed here rather than
fixed in this task: `docs/configuration.md` (task 59's file, not this one's)
does not yet document `dataprism.identity.resolver` at all — this page is,
for now, that property's only public documentation.
