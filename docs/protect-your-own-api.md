# Protect your own API: a YAML-only walkthrough

You have a flat JSON REST API — one JSON object per response, no nested
objects — and you want Data Prism to sit in front of it and answer MCP
callers with a pseudonymised view instead of your raw data. This walkthrough
takes you from that API to a working `get_entity_context` call, writing only
YAML. No Java class, no `pom.xml`, no `META-INF` registration step appears
anywhere below.

That is `data-prism-connectors-rest`'s configuration-driven JSON REST mode: a
published artifact
(`io.github.aindriub:data-prism-connectors-rest`), loaded into a running
`data-prism-server` the same way any other reviewed adapter is —
`-Dloader.path` — but configured entirely by a `json-sources:` catalogue
instead of compiled Java. It has one real limit, stated here so you can check
it against your own API before going further: its field resolver never
descends into a nested object, so it only covers a source whose response is
one flat JSON object — scalar fields, or arrays of them. If your response
nests objects, needs custom fetch logic beyond a single templated `GET`, or
needs a model this flat catalogue cannot express, stop here and read
[`docs/extending.md`](extending.md) instead; nothing below lifts that limit.

This walkthrough does not restate the full `dataprism.*` configuration
vocabulary — that is [`docs/configuration.md`](configuration.md) — or the two
MCP tools an agent calls — that is [`docs/tools.md`](tools.md). It is the
YAML-only counterpart to [`docs/extending.md`](extending.md): the same
destination, without writing code.

## What you need

- A running `data-prism-server` distribution and the
  `data-prism-connectors-rest` jar alongside it. This walkthrough runs both
  from this repository's own `mvn package` output
  (`data-prism-server/target/data-prism-server-0.2.0.jar` and
  `data-prism-connectors-rest/target/data-prism-connectors-rest-0.2.0.jar`),
  which is exactly the artifact Maven Central serves under the same
  coordinates and version — nothing here is specific to a from-source build.
  "Build the jars, then start the two fixtures" below gives the exact build
  command; every path in this walkthrough is relative to the repository
  root, and every command below is run from there.
- Your own flat JSON REST API. This walkthrough stands in
  `data-prism-quickstart-fixtures` for it — a real, already-built HTTPS
  service in this repository with a `/customers/{id}` endpoint and two
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
These are the same three `keytool` invocations `QuickstartSmokeIT`
(`data-prism-quickstart-extension`) and `docker/certs-init/generate-certs.sh`
both run, adapted to loopback-only use here:

```sh
keytool -genkeypair -alias walkthrough -keyalg RSA -keysize 2048 -validity 2 \
  -keystore walkthrough.p12 -storetype PKCS12 \
  -storepass walkthrough-demo-only -keypass walkthrough-demo-only \
  -dname "CN=data-prism-walkthrough" \
  -ext "san=ip:127.0.0.1,dns:localhost"

keytool -exportcert -alias walkthrough -keystore walkthrough.p12 \
  -storetype PKCS12 -storepass walkthrough-demo-only -file walkthrough.cer

keytool -importcert -alias walkthrough -file walkthrough.cer \
  -keystore walkthrough-trust.p12 -storetype PKCS12 \
  -storepass walkthrough-demo-only -noprompt
```

`walkthrough.p12` is the keystore the two fixtures serve HTTPS from;
`walkthrough-trust.p12` is the truststore the server trusts it with below.
Neither is committed or reused anywhere else — delete all three generated
files (`walkthrough.p12`, `walkthrough.cer`, `walkthrough-trust.p12`) once
you are done with this walkthrough.

Start `data-prism-quickstart-fixtures` — the flat JSON REST API this
walkthrough stands in for your own — on port 8543:

```sh
java -jar data-prism-quickstart-fixtures/target/data-prism-quickstart-fixtures-0.2.0.jar \
  --server.port=8543 \
  --server.ssl.key-store=file:walkthrough.p12 \
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
tokens from — on port 8544, in a second terminal:

```sh
java -jar data-prism-quickstart-issuer/target/data-prism-quickstart-issuer-0.2.0.jar \
  --server.port=8544 \
  --server.ssl.key-store=file:walkthrough.p12 \
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
-Dloader.path=data-prism-connectors-rest/target/data-prism-connectors-rest-0.2.0.jar
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
the JVM's own trust store system properties.

```sh
export DATAPRISM_WALKTHROUGH_HMAC_KEY=walkthrough-demo-hmac-key-material-32-bytes-plus

java \
  -Djavax.net.ssl.trustStore=walkthrough-trust.p12 \
  -Djavax.net.ssl.trustStorePassword=walkthrough-demo-only \
  -Djavax.net.ssl.trustStoreType=PKCS12 \
  -Dloader.path=data-prism-connectors-rest/target/data-prism-connectors-rest-0.2.0.jar \
  -Ddataprism.json-sources.config-location=file:examples/json-sources/customer-api.yaml \
  -jar data-prism-server/target/data-prism-server-0.2.0.jar \
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
id: 6967a06e-8550-4d0c-88e7-f1879e688c19
event: message
data: {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"entityType\":\"CUSTOMER\",\"subject\":\"SUBJ-3WR4\",\"sources\":{\"ORGANISATION_IDENTITY-6BE1NJ46\":\"ANSWERED\"},\"findings\":[],\"entity\":{\"customerName\":\"Casey Okafor (5K38)\",\"email\":\"[REDACTED]\",\"status\":\"ACTIVE\"}}"}],"isError":false,"structuredContent":{"entityType":"CUSTOMER","subject":"SUBJ-3WR4","sources":{"ORGANISATION_IDENTITY-6BE1NJ46":"ANSWERED"},"findings":[],"entity":{"customerName":"Casey Okafor (5K38)","email":"[REDACTED]","status":"ACTIVE"}}}}
```

Neither raw value appears. `customerName` is a stable synthetic substitute in
the `PERSON_NAME` namespace (the catalogue's own `namespace:` above), `email`
is redacted outright per its `action: REDACT`, and `status` passes through
unchanged because the catalogue marked it `nonSensitive`. The subject itself
(`SUBJ-3WR4`) is a pseudonym too, not `1001`. This ran with
`-Dloader.path=data-prism-connectors-rest-0.2.0.jar` alone — no second jar,
no custom extension, no `IdentityResolver` bean compiled anywhere — and
against the catalogue exactly as it is committed in
`examples/json-sources/customer-api.yaml`, comments included: the transport
this response actually dialled is stated in that one file, once.

## Prove it fails closed

An incomplete catalogue — the same file with `timeout:` deleted — refuses at
startup, before any traffic reaches the source, naming the offending source
and the missing key:

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

## Close-out

Every code fence above was executed, not transcribed:

| What it shows | Command that produced it |
|---|---|
| The two fixtures' health checks | `curl -sk https://127.0.0.1:8543/health` and `curl -sk https://127.0.0.1:8544/health`, against processes started with the `java -jar data-prism-quickstart-fixtures/...`/`data-prism-quickstart-issuer/...` commands in "Build the jars, then start the two fixtures" |
| Server starts, no `dataprism.sources.customer-api` entry anywhere on the command line | `java -Dloader.path=data-prism-connectors-rest/target/data-prism-connectors-rest-0.2.0.jar -Ddataprism.json-sources.config-location=file:examples/json-sources/customer-api.yaml -jar data-prism-server/target/data-prism-server-0.2.0.jar ...` (server log, "Started DataPrismServerApplication") |
| `GET /health` | `curl -s http://127.0.0.1:8080/health` |
| `MISSING_IDENTITY_RESOLVER` | the same server command with `--dataprism.identity.resolver=pass-through` removed |
| `UNSUPPORTED_IDENTITY_RESOLVER` | the same server command with `--dataprism.identity.resolver=probabilistic-match` |
| The pseudonymised `get_entity_context` response, SSE frame included | the `initialize` / `notifications/initialized` / `tools/call` sequence in "Get a token and call it", run against a token freshly minted by `curl -sk -X POST https://127.0.0.1:8544/token` |
| The missing-`timeout` refusal | the same server command, catalogue copy with `timeout:` deleted |

`walkthrough.p12`/`walkthrough-trust.p12` above are a throwaway self-signed
keystore and truststore generated with the `keytool -genkeypair`/`-exportcert`/
`-importcert` commands in "Build the jars, then start the two fixtures" — the
same three invocations `QuickstartSmokeIT` (`data-prism-quickstart-extension`)
and `docker/certs-init/generate-certs.sh` both run — never committed, never
reused. `data-prism-quickstart-fixtures` and `data-prism-quickstart-issuer`
were started as plain `java -jar` processes on `127.0.0.1`, standing in for
your own API and your own identity provider respectively; nothing about the
JSON REST connector itself depends on either being a Data Prism fixture
rather than your real infrastructure.

One defect surfaced while preparing this walkthrough, filed here rather than
fixed in this task: `docs/configuration.md` (task 59's file, not this one's)
does not yet document `dataprism.identity.resolver` at all — this page is,
for now, that property's only public documentation.
