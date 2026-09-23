# Extending Data Prism: writing a reviewed adapter

If your source is a JSON REST API whose response is either flat or nests
objects at most one level deep, you almost certainly do not need this guide.
Read [`docs/protect-your-own-api.md`](protect-your-own-api.md) instead: a
YAML-only walkthrough that protects such a source with no Java class, no
`pom.xml`, and no `META-INF` registration step, using the same
`data-prism-connectors-rest` configuration-driven JSON REST mode summarised
below.

This guide is for the cases that YAML-only path cannot cover: a response that
nests objects two levels or more, custom fetch logic beyond a single
templated `GET`, or a model no allowlisted catalogue can express. For any of
those, this is the path a consumer walks to point Data Prism at their own
API: write a `DataSourceAdapter`, write (or reuse) an `IdentityResolver`,
classify the response model with `@LlmExposedModel`, shape the pom, register
the extension, load it into the server, and configure it. It assumes nothing
about this codebase beyond what `README.md` already says: Data Prism is a
privacy layer between MCP clients and your API, and it never exposes a field
nobody classified.

The configuration-driven JSON REST mode `docs/protect-your-own-api.md` walks
through is real and already shipped, as a normal published artifact,
`data-prism-connectors-rest` — no annotated Java model, no `pom.xml`, no
compiled adapter class. An operator loads that jar the same way as any
reviewed extension (`-Dloader.path`) and writes a YAML catalogue instead:
`dataprism.json-sources.config-location` names a file whose `json-sources:`
entries state a transport (`base-url`, a `path` template, `timeout`), a
`model-version` tag, and an allowlisted `fields:` catalogue — one entry
per JSON property, each an identifier, `nonSensitive`, classified, or a
`nested: <name>` pointer into a `nested-catalogues:` entry — the same
vocabulary `@SensitiveData`/`@NonSensitive`/`@InternalIdentifier` express
below
(`data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonSource.java:8-34`,
`.../ConfiguredJsonSourcesAutoConfiguration.java:82`).

That mode has a real limit worth knowing before choosing it: a `nested:`
field's own catalogue is exactly one level deep, and it carries no identifier
of its own — it inherits its subject from the enclosing record — so its
leaves may be `nonSensitive` or classified only; `identifier: true` and a
further `nested:` are both refused there. `descendable` only ever answers
true for one of a source's own minted
nested-catalogue tokens, never recursively
(`data-prism-connectors-rest/src/main/java/io/github/aindriub/dataprism/connectors/rest/ConfiguredJsonFieldMetadataResolver.java`).
There is also no dotted path or JSONPath anywhere in this grammar: every
`fields:`/`nested-catalogues:` key, and `subject-json-path` itself, is a
single bare, exact-match property name. If your source's response nests
objects two levels or more, needs custom fetch logic beyond a single
templated `GET`, or needs a model no such catalogue can express, that limit
is why this guide exists: the Java-first path below has no such ceiling.
Configuration for the JSON REST mode is not covered further here —
see [`docs/protect-your-own-api.md`](protect-your-own-api.md) for the
worked walkthrough and [`docs/configuration.md`](configuration.md) for its
full configuration vocabulary — because this guide is about the path that
requires writing code.

The worked example this guide cites throughout is
`data-prism-quickstart-extension`, a real module in this repository that CI
builds and exercises end to end via `QuickstartSmokeIT`
(`data-prism-quickstart-extension/src/test/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartSmokeIT.java:34-40`).
Every snippet below is either copied verbatim from that module (or another
cited file) or is output actually captured by running a command shown beside
it — nothing here was composed by hand and left unchecked.

This guide does not restate the `dataprism.*` configuration vocabulary; that
is [`docs/configuration.md`](configuration.md). It does not describe the two
MCP tools an agent calls; that is `docs/tools.md`. It does not re-specify the
platform's design; that is `docs/pack.md`. It is the missing piece: how the
pieces those documents assume already exist get built.

## The `-Dloader.path` trap

Read this before the pom section, because the mistake it describes is
invisible until the packaged server is actually running against your data.

`-Dloader.path` (and its environment-variable equivalent, `LOADER_PATH`, read
by Spring Boot's `PropertiesLauncher`) adds your extension jar's **own**
classes and resources to the running server's classpath. It does **not** add
that jar's dependencies. Your extension is a plain jar, not a Spring Boot
executable with its libraries bundled inside — so if your `pom.xml` declares
a real (non-`provided`) dependency your adapter code needs at runtime, the
build succeeds, `mvn package` succeeds, and the failure only surfaces the
first time the server tries to construct a bean that touches a class from
that dependency — a `ClassNotFoundException` or `NoClassDefFoundError` at
startup or, worse, on the first request that reaches your adapter. This is
already documented in this repository, in exactly two places, and nowhere a
consumer building their own extension would find it:

> A plain jar, not a Spring Boot executable: `-Dloader.path` adds only this
> jar's own classes and resources to the running server's classpath, never
> its dependencies, so everything below other than `data-prism-core` and
> `data-prism-annotations` is scoped "provided" — it must already be on
> `data-prism-server`'s own classpath, and `spring-boot-starter-web` and
> `spring-boot-starter-oauth2-resource-server` (`data-prism-server`'s own
> dependencies) put it there.

— `data-prism-quickstart-extension/pom.xml:18-26`

> `LOADER_PATH`: a directory, read by Spring Boot's `PropertiesLauncher`
> exactly as
> `ServerPackagingIT.executableLoadsAReviewedAdapterExtensionFromLoaderPath`
> proves for a single jar passed via `-Dloader.path` — `PropertiesLauncher`
> reads the same property from this environment variable (its own
> upper-case, underscore convention), and, given a directory rather than one
> jar, adds every `*.jar` placed directly inside it to the classpath.

— `docker/distribution/Dockerfile:33-35`

The consequence is the pom shape in the next section: everything your
extension needs that `data-prism-server` already carries on its own
classpath (`spring-web`, `spring-boot-autoconfigure`, `data-prism-core`,
`data-prism-annotations`) must be declared `provided`, never a normal
compile-scope dependency. `provided` tells Maven "compile against this, but
do not put a copy of it in the jar" — which is correct here precisely because
`-Dloader.path` will never supply that copy either. Anything your extension
needs that `data-prism-server` does *not* already carry has no home: it
cannot be bundled by `-Dloader.path`, so it cannot be a real runtime
dependency of a loader-path extension at all.

## Implement `DataSourceAdapter`

`DataSourceAdapter<T>` is the SPI for one external system:

```java
public interface DataSourceAdapter<T> {

    String sourceName();

    Class<T> responseType();

    T fetch(DataRequest request);
}
```

`data-prism-core/src/main/java/io/github/aindriub/dataprism/core/DataSourceAdapter.java:10-17`

- `sourceName()` is the string that later binds this adapter to a
  `dataprism.sources.<name>` configuration entry — see the binding section
  below; nothing else about this class name, package or bean name matters
  for that.
- `responseType()` returns the `@LlmExposedModel`-annotated class this
  adapter produces.
- `fetch(DataRequest request)` does the actual call. `DataRequest` carries
  only `entityType`, `subjectId` and a parameters map your adapter chooses to
  read — never a caller-supplied URL, path or query
  (`data-prism-core/src/main/java/io/github/aindriub/dataprism/core/DataRequest.java:6-12`).

The worked implementation:

```java
final class QuickstartCustomerAdapter implements DataSourceAdapter<CustomerModel> {

    private static final Logger LOG = LoggerFactory.getLogger(QuickstartCustomerAdapter.class);

    /** Must equal the {@code dataprism.sources.*} key this adapter is configured under. */
    static final String SOURCE_NAME = "customer";

    private final RestClient client;

    QuickstartCustomerAdapter(RestClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public Class<CustomerModel> responseType() {
        return CustomerModel.class;
    }

    @Override
    public CustomerModel fetch(DataRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return client.get()
                    .uri("/customers/{id}", request.subjectId())
                    .retrieve()
                    .body(CustomerModel.class);
        } catch (HttpClientErrorException.NotFound absent) {
            LOG.debug("source {} holds no record for the requested subject", SOURCE_NAME);
            return null;
        }
    }
}
```

`data-prism-quickstart-extension/src/main/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartCustomerAdapter.java:22-58`

Two things worth carrying over even though they are not enforced by the
platform: the subject id is passed as a URI *template variable*
(`"/customers/{id}"`, `request.subjectId()`) so `RestClient` encodes it,
rather than being concatenated into the path; and a 404 is treated as data —
this source has nothing for the subject — by returning `null`, not by
throwing. That is what lets the orchestrator record this source's outcome as
`NO_DATA` — a per-source status surfaced next to the result, not a
consistency finding — instead of tripping this source's circuit breaker
(`data-prism-orchestration/src/main/java/io/github/aindriub/dataprism/orchestration/SourceOutcome.java:19-23`,
`QuickstartCustomerAdapter.java:12-21`).

## Implement `IdentityResolver`

`IdentityResolver` is how a subject's per-source keys relate to one canonical
identity:

```java
public interface IdentityResolver {

    /** The canonical subject a source record belongs to. */
    CanonicalId resolve(SourceRef ref);

    /**
     * The per-source keys to fetch for a subject. A source absent from the result
     * is not queried, which is how a subject known to only some systems avoids
     * pointless calls and misleading "no data" findings.
     */
    List<SourceRef> expand(CanonicalId id, List<String> sourceNames);
```

`data-prism-core/src/main/java/io/github/aindriub/dataprism/core/IdentityResolver.java:22-32`

Most integrations do not need a custom implementation. `PassThroughIdentityResolver`
is the honest default:

```java
public final class PassThroughIdentityResolver implements IdentityResolver {

    @Override
    public CanonicalId resolve(SourceRef ref) {
        return new CanonicalId(Objects.requireNonNull(ref, "ref").key());
    }

    @Override
    public List<SourceRef> expand(CanonicalId id, List<String> sourceNames) {
        Objects.requireNonNull(id, "id");
        return sourceNames.stream().map(name -> new SourceRef(name, id.value())).toList();
    }
}
```

`data-prism-core/src/main/java/io/github/aindriub/dataprism/core/PassThroughIdentityResolver.java:14-26`

Use it **only** when every configured source genuinely keys its records on
the same identifier (`README.md:160-163`): it does nothing, treating the
subject id you were asked for as every source's own key. If your sources
disagree about identity — different customer numbers, a probabilistic match
on name and date of birth, a master data service you must call first — write
your own `IdentityResolver`; that difficulty is exactly what the interface
exists to hold, and nothing in the platform will paper over a wrong pass-through.

The worked example wires `PassThroughIdentityResolver` because its one
fixture source shares a single id:

```java
    @Bean
    @ConditionalOnMissingBean
    IdentityResolver quickstartIdentityResolver() {
        // Every source the quickstart configures already keys on the same
        // subjectId, so the honest default resolver is the correct one — see
        // PassThroughIdentityResolver's own Javadoc.
        return new PassThroughIdentityResolver();
    }
```

`data-prism-quickstart-extension/src/main/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartExtensionAutoConfiguration.java:36-43`

## Classify the model with `@LlmExposedModel`

A response type is never exposed through MCP unless it carries
`@LlmExposedModel`, and every field of an annotated type must then carry one
of four annotations: `@SensitiveData`, `@NonSensitive`, `@InternalIdentifier`
or `@SubjectIdentifier` — the exact set `LlmExposedModelProcessor` checks for
(`data-prism-processor/src/main/java/io/github/aindriub/dataprism/processor/LlmExposedModelProcessor.java:43-45`).
The worked model:

```java
@LlmExposedModel
public record CustomerModel(

        @InternalIdentifier
        String customerId,

        @SensitiveData(
                classifications = DataClassification.PII,
                namespace = PrivacyNamespace.PERSON_NAME,
                suggestedAction = PrivacyAction.SYNTHESIZE)
        String customerName,

        @SensitiveData(
                classifications = DataClassification.CONTACT,
                namespace = PrivacyNamespace.EMAIL,
                suggestedAction = PrivacyAction.REDACT)
        String email,

        @NonSensitive(reason = "Enumerated lifecycle state; no free text and no bearing on identity")
        String status) {
}
```

`data-prism-quickstart-extension/src/main/java/io/github/aindriub/dataprism/quickstart/extension/CustomerModel.java:17-37`

- `@InternalIdentifier` marks the field holding this record's own correlation
  id — the default pseudonymisation subject for any `@SensitiveData` field on
  the same record that does not name one explicitly
  (`data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/InternalIdentifier.java:10-19`).
- `@SensitiveData` classifies a field: `classifications` says what kind of
  data it is, `namespace` is what makes the same person's name synthesize to
  the same value across sources, and `suggestedAction` is what the model
  author believes should happen — a suggestion policy may tighten but never
  loosen
  (`data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/SensitiveData.java:9-38`).
- `@NonSensitive` asserts a field is safe to emit unchanged and requires a
  `reason()` — that string is the review artefact; "not sensitive" is
  explicitly called out as not a reason
  (`data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/NonSensitive.java:9-25`).
- `@SubjectIdentifier` is for a record describing more than one subject — an
  applicant and a guarantor — where `@InternalIdentifier`'s single default
  is wrong
  (`data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/SubjectIdentifier.java:9-25`).

Two more exist for cases the quickstart model does not need: `@SensitiveObject`
marks a nested type as safe to descend into rather than treated as an
unclassified blob
(`data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/SensitiveObject.java:9-25`),
and `undeclaredFields()` on `@LlmExposedModel` lets a large legacy model state
one retrofit decision for every field nobody annotated, instead of annotating
each of them
(`data-prism-annotations/src/main/java/io/github/aindriub/dataprism/annotations/LlmExposedModel.java:26-37`).

### What the processor actually rejects — proven by compiling

The rule above is not a style preference; `LlmExposedModelProcessor` fails
the build. To see the real diagnostic rather than take that on faith, this
throwaway file was written outside the repository (it is not part of this
module and does not exist in this codebase) with one field left unclassified:

```java
import io.github.aindriub.dataprism.annotations.InternalIdentifier;
import io.github.aindriub.dataprism.annotations.LlmExposedModel;

@LlmExposedModel
public record BadModel(
        @InternalIdentifier
        String customerId,
        String unclassifiedField) {
}
```

It was compiled directly against this repository's own built
`data-prism-annotations` and `data-prism-processor` jars, with the processor
placed on `-processorpath`, the same way `annotationProcessorPaths` places it
for Maven:

```
$ javac -cp data-prism-annotations-0.3.0.jar \
    -processorpath data-prism-processor-0.3.0.jar:data-prism-annotations-0.3.0.jar \
    -d out BadModel.java
BadModel.java:8: error: field is on an @LlmExposedModel but carries no classification. Add @SensitiveData, or @NonSensitive(reason = "...") stating why it is safe to expose.
        String unclassifiedField) {
               ^
1 error
```

That is the literal, only `Diagnostic.Kind.ERROR` this processor ever emits
(`data-prism-processor/src/main/java/io/github/aindriub/dataprism/processor/LlmExposedModelProcessor.java:70-75`).
A type that sets `undeclaredFields()` to anything other than the default is
exempt from this check entirely — it has already made the statement once, for
the whole class (`LlmExposedModelProcessor.java:58-64`).

## The pom shape

`data-prism-quickstart-extension/pom.xml` is where the shape below comes
from, but it cannot be copied verbatim into a pom outside this repository.
Its dependencies carry no `<version>`, and its `annotationProcessorPaths`
names `${project.version}`:

```xml
  <dependencies>
    <dependency>
      <groupId>io.github.aindriub</groupId>
      <artifactId>data-prism-core</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>io.github.aindriub</groupId>
      <artifactId>data-prism-annotations</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework</groupId>
      <artifactId>spring-web</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-autoconfigure</artifactId>
      <scope>provided</scope>
    </dependency>
```

`data-prism-quickstart-extension/pom.xml:38-58`

That works inside this repository, and only inside it, because this module
inherits from the reactor's own parent pom
(`data-prism-quickstart-extension/pom.xml:7-11`), which pins every
`io.github.aindriub` artifact to `${project.version}`, imports
`spring-boot-dependencies` to pin `spring-web` and
`spring-boot-autoconfigure`, and sets `<maven.compiler.release>21</maven.compiler.release>`
(`pom.xml:67`). A consumer project has no relationship to that parent, and
loses all three inherited settings, not just the versions: copying the
dependency block above as shown gets a missing-version error for the two
`io.github.aindriub` dependencies; copying `${project.version}` into
`annotationProcessorPaths` (below) asks for a `data-prism-processor` at *the
consumer's own project version*, an artifact that does not exist; and with
no `release` set at all, `maven-compiler-plugin` falls back to its own
default of `1.8`, at which point a `record` (used below) is a syntax error
and `data-prism-core-0.3.0.class` files — compiled for 21 — fail to load
with `class file has wrong version 65.0`.

The version-complete equivalent, standing alone, with no parent from this
repository. It assumes your own pom already has the usual top-level
`<modelVersion>`, `groupId`, `artifactId`, `version` and
`<packaging>jar</packaging>` — only the three blocks below are specific to
depending on Data Prism:

```xml
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <data-prism.version>0.3.0</data-prism.version>
    <spring-boot.version>3.5.16</spring-boot.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>io.github.aindriub</groupId>
      <artifactId>data-prism-core</artifactId>
      <version>${data-prism.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>io.github.aindriub</groupId>
      <artifactId>data-prism-annotations</artifactId>
      <version>${data-prism.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework</groupId>
      <artifactId>spring-web</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-autoconfigure</artifactId>
      <scope>provided</scope>
    </dependency>
  </dependencies>
```

`spring-boot.version` (`3.5.16`) is the exact Spring Boot version the 0.3.0
server distribution was built against — importing its
`spring-boot-dependencies` BOM is what lets `spring-web` and
`spring-boot-autoconfigure` above go unversioned safely, resolving to the
same versions already on the running server's classpath, which is the whole
point of marking them `provided` in the first place. The reason for
`provided` itself is otherwise unchanged from the trap section above:
`data-prism-server` already carries all four dependencies on its own
classpath, `-Dloader.path` never supplies them, and a real
(non-`provided`) scope would bundle a redundant copy this jar does not need
to ship. (The in-repo module also
carries three `test`-scope dependencies and two MCP SDK `test`-scope
dependencies at lines 60-94 — those exist only so the Maven reactor builds
the packaged artifacts this module's own smoke test starts as
subprocesses; a consumer's extension pom has no reason to carry them.)

This was verified, not assumed: a throwaway project's pom was assembled by
pasting the `<properties>`/`<dependencyManagement>`/`<dependencies>` block
above and the `<plugin>` block below unmodified into a pom whose only other
content is the top-level fields already assumed (`groupId`, `artifactId`,
`version`, `packaging`) — nothing added, nothing implied. Alongside a
minimal `DataSourceAdapter` and an `@LlmExposedModel` record, it was built
with `mvn package` against a clean local repository with no other
data-prism artifacts in it, resolving `data-prism-core`,
`data-prism-annotations` and `data-prism-processor` `0.3.0` from Maven
Central and `spring-web` `6.2.19`/`spring-boot-autoconfigure` `3.5.16` from
the imported BOM — the same Spring Boot version the 0.3.0 server
distribution itself was built against. The build produced a jar; nothing in
this paragraph is aspirational.

The annotation processor is configured separately, and only here — with an
explicit version, not `${project.version}`, for the reason above. If your
pom already has a `<build><plugins>` section, add this `<plugin>` inside it
instead of duplicating the wrapper:

```xml
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <annotationProcessorPaths>
            <path>
              <groupId>io.github.aindriub</groupId>
              <artifactId>data-prism-processor</artifactId>
              <version>${data-prism.version}</version>
            </path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
    </plugins>
  </build>
```

The `<plugin>` itself is adapted from
`data-prism-quickstart-extension/pom.xml:98-113`, which reads identically
except for the version (the `<build><plugins>` wrapper around it is already
present elsewhere in that module's pom, so its own citation does not include
one). That module's own comment explains the placement:

> On the processor path, not the compile classpath: this module depends on
> the `@LlmExposedModel` classification check running, not on the checker's
> own classes.

— `data-prism-quickstart-extension/pom.xml:102-104`

`data-prism-processor` must never appear as a `<dependency>` — only under
`annotationProcessorPaths`, as above. It is a build-time tool that runs
during your compile, not a class your adapter code calls; giving it a real
dependency scope would put a build tool on the classpath a `-Dloader.path`
extension eventually ships from.

## Register the extension

Spring Boot needs to be told which auto-configuration class to load from a
jar arriving via `-Dloader.path`, since nothing on that path is
component-scanned. Registration is one line in one file:

```
io.github.aindriub.dataprism.quickstart.extension.QuickstartExtensionAutoConfiguration
```

`data-prism-quickstart-extension/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

That class is an ordinary `@AutoConfiguration` providing your `IdentityResolver`
and `DataSourceAdapter` beans:

```java
@AutoConfiguration
public class QuickstartExtensionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IdentityResolver quickstartIdentityResolver() {
        // Every source the quickstart configures already keys on the same
        // subjectId, so the honest default resolver is the correct one — see
        // PassThroughIdentityResolver's own Javadoc.
        return new PassThroughIdentityResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    DataSourceAdapter<CustomerModel> quickstartCustomerAdapter(
            @Value("${dataprism.sources.customer.base-url}") String baseUrl,
            @Value("${dataprism.sources.customer.timeout}") String timeout) {
        Duration readTimeout = DurationStyle.detectAndParse(timeout);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) readTimeout.toMillis());
        requestFactory.setReadTimeout((int) readTimeout.toMillis());
        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        return new QuickstartCustomerAdapter(client);
    }
}
```

`data-prism-quickstart-extension/src/main/java/io/github/aindriub/dataprism/quickstart/extension/QuickstartExtensionAutoConfiguration.java:33-60`

Note what this class reads its base URL and timeout from — `dataprism.sources.customer.*`
— which is exactly the vocabulary [`docs/configuration.md`](configuration.md)
describes for `dataprism.sources.<name>`; nothing here lets an MCP caller
choose a host or path.

## Load the extension

With the jar built (`mvn package` in your extension module), two equivalent
ways to load it into the packaged server exist, both documented already in
`README.md:160-176`:

```bash
LOADER_PATH=/opt/data-prism/extensions \
  java -jar data-prism-server/target/data-prism-server-0.3.0.jar \
  --spring.config.additional-location=file:/etc/data-prism/application.yaml
```

`README.md:167-171`

— `LOADER_PATH` names a directory; every `*.jar` placed directly inside it is
added. The single-jar form used directly is `-Dloader.path=/path/to/your-extension.jar`
as a JVM system property, passed before `-jar`. Both are the same mechanism
`ServerPackagingIT.executableLoadsAReviewedAdapterExtensionFromLoaderPath`
proves against a real packaged server jar
(`docker/distribution/Dockerfile:34`), and both are subject to the trap
above: only the jar's own classes travel this way.

## Bind `dataprism.sources.<name>` to your adapter

Binding is by **return value**, not by bean name, class name or annotation:
the server compares the set of names configured under `dataprism.sources`
against the set of strings every `DataSourceAdapter` bean's `sourceName()`
returns, and refuses startup unless the two sets are identical:

```java
        Set<String> configured=properties.getSources().keySet(); Set<String> supplied=adapterList.stream().map(DataSourceAdapter::sourceName).collect(Collectors.toSet());
        if(configured.isEmpty()) throw new DataPrismConfigurationException("MISSING_SOURCE_ADAPTER","dataprism.sources must name at least one reviewed adapter");
        if(!configured.equals(supplied)) throw new DataPrismConfigurationException("UNRESOLVED_SOURCE_ADAPTER","configured sources and DataSourceAdapter beans differ");
```

`data-prism-spring-boot-autoconfigure/src/main/java/io/github/aindriub/dataprism/spring/boot/DataPrismContractValidator.java:27-29`

The Spring bean method name (`quickstartCustomerAdapter`, above) and the
class name (`QuickstartCustomerAdapter`) are never consulted for this check —
only the string `sourceName()` returns is. Configure your source under a
different key than that string, and every source in `dataprism.sources`
still refuses to start, with `UNRESOLVED_SOURCE_ADAPTER`. This refusal is
proven in this repository's own test suite
(`data-prism-server/src/test/java/io/github/aindriub/dataprism/server/ServerStartupTest.java:55`),
and it was reproduced here directly against the real packaged jars — the
quickstart extension's `sourceName()` returns `"customer"`, so configuring
`dataprism.sources.customer` (satisfied) alongside a second, unbacked
`dataprism.sources.wrongsource` entry (no adapter returns that name)
triggers the same refusal:

```
$ java -Dloader.path=$EXTENSION_JAR -jar $SERVER_JAR \
    --dataprism.sources.customer.base-url=https://customer.example \
    --dataprism.sources.customer.timeout=2s \
    --dataprism.sources.wrongsource.base-url=https://wrongsource.example \
    --dataprism.sources.wrongsource.timeout=2s \
    [... rest of the required dataprism.* configuration, see docs/configuration.md ...]
...
Caused by: io.github.aindriub.dataprism.spring.boot.DataPrismConfigurationException: UNRESOLVED_SOURCE_ADAPTER: configured sources and DataSourceAdapter beans differ
	at io.github.aindriub.dataprism.spring.boot.DataPrismContractValidator.validateIntegrations(DataPrismContractValidator.java:29)
	at io.github.aindriub.dataprism.spring.boot.DataPrismAutoConfiguration.dataPrismPropertiesValidated(DataPrismAutoConfiguration.java:98)
```

`$EXTENSION_JAR` and `$SERVER_JAR` were the built
`data-prism-quickstart-extension-0.3.0.jar` and `data-prism-server-0.3.0.jar`
from this repository's own `target/` directories; the omitted arguments are
the same security, privacy, audit, metrics and Hazelcast configuration
`docs/configuration.md` requires for any protected deployment and are unrelated
to this refusal.

The full `dataprism.sources.<name>` shape — `base-url`, `timeout`, mTLS and
credential references — is documented in the `dataprism.*` vocabulary table of
[`docs/configuration.md`](configuration.md); this section covers only how the
name binds to your code.
