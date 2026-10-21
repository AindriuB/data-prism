package io.github.aindriub.dataprism.hazelcast;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.codec.MCChangeClusterStateCodec;
import com.hazelcast.client.impl.protocol.codec.MCChangeClusterVersionCodec;
import com.hazelcast.client.impl.protocol.codec.MCGetClusterMetadataCodec;
import com.hazelcast.client.impl.protocol.codec.MCGetMemberConfigCodec;
import com.hazelcast.client.impl.protocol.codec.MCGetSystemPropertiesCodec;
import com.hazelcast.client.impl.protocol.codec.MCGetThreadDumpCodec;
import com.hazelcast.client.impl.protocol.codec.MCGetTimedMemberStateCodec;
import com.hazelcast.client.impl.protocol.codec.MCPromoteLiteMemberCodec;
import com.hazelcast.client.impl.protocol.codec.MCReloadConfigCodec;
import com.hazelcast.client.impl.protocol.codec.MCRunConsoleCommandCodec;
import com.hazelcast.client.impl.protocol.codec.MCRunGcCodec;
import com.hazelcast.client.impl.protocol.codec.MCRunScriptCodec;
import com.hazelcast.client.impl.protocol.codec.MCShutdownClusterCodec;
import com.hazelcast.client.impl.protocol.codec.MCShutdownMemberCodec;
import com.hazelcast.cluster.ClusterState;
import com.hazelcast.config.ClassFilter;
import com.hazelcast.config.Config;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import com.hazelcast.spi.properties.ClusterProperty;
import com.hazelcast.spi.properties.HazelcastProperties;
import io.github.aindriub.dataprism.hazelcast.PrivacyClusterRefusal.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What the build forces on a Hazelcast member (design 4.5): Jet, user-code deployment and phone-home off, SQL
 * unreachable, a Java serialisation allowlist, and a Management Center trusted-interface list that no client
 * matches. No test here starts a member with phone-home on: a started member posts telemetry.
 */
class HazelcastHardeningTest {

    /** Answer 4 of the spike (values and processors), then the Java form of an error reply; see PrivacyCluster. */
    private static final Set<String> ALLOWED_CLASSES = Set.of("java.lang.String", "java.lang.Integer",
            "java.lang.Boolean", "java.time.Ser",
            "java.lang.Throwable", "java.lang.Exception", "java.lang.RuntimeException",
            "java.lang.IllegalStateException", "java.lang.IllegalMonitorStateException",
            "java.lang.StackTraceElement", "[Ljava.lang.StackTraceElement;", "java.util.Collections$EmptyList",
            "com.hazelcast.core.HazelcastException", "com.hazelcast.core.OperationTimeoutException",
            "com.hazelcast.core.HazelcastInstanceNotActiveException",
            "com.hazelcast.transaction.TransactionException",
            "com.hazelcast.splitbrainprotection.SplitBrainProtectionException",
            "com.hazelcast.nio.serialization.HazelcastSerializationException",
            "java.lang.SecurityException", "java.lang.ClassCastException", "java.lang.IllegalArgumentException",
            "java.lang.NullPointerException", "java.lang.NumberFormatException",
            "java.lang.IndexOutOfBoundsException", "java.lang.StringIndexOutOfBoundsException",
            "java.lang.ArrayIndexOutOfBoundsException", "java.time.DateTimeException",
            "java.time.format.DateTimeParseException");
    private static final Set<String> ALLOWED_PREFIXES = Set.of("io.github.aindriub.dataprism.hazelcast.",
            "com.hazelcast.spi.exception.");

    private final List<PrivacyCluster> clusters = new ArrayList<>();
    private final List<HazelcastInstance> raw = new ArrayList<>();
    private HazelcastInstance client;

    @AfterEach
    void stop() {
        if (client != null) {
            client.shutdown();
        }
        raw.forEach(i -> i.getLifecycleService().terminate());
        clusters.forEach(PrivacyCluster::close);
    }

    // ---- members ----

    private PrivacyCluster membershipMember() {
        int port = FreePorts.consecutive(1);
        PrivacyCluster cluster = PrivacyCluster.embedded(ClusterMembership
                .tcpIp(TestMembers.uniqueName("hardening-m"), List.of("127.0.0.1:" + port)).withPort(port)
                .withInterface("127.0.0.1"), false);
        clusters.add(cluster);
        return cluster;
    }

    /** A supplied config with Jet and user-code deployment on, as an integrator might hand over. Phone-home off. */
    private static Config suppliedConfig(String name, int port) {
        Config config = new Config().setClusterName(name);
        config.getJetConfig().setEnabled(true);
        config.getUserCodeDeploymentConfig().setEnabled(true);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
        var join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).addMember("127.0.0.1:" + port);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.setProperty("hazelcast.socket.bind.any", "false");
        return config;
    }

    private PrivacyCluster configMember() {
        int port = FreePorts.consecutive(1);
        PrivacyCluster cluster = PrivacyCluster.embedded(
                suppliedConfig(TestMembers.uniqueName("hardening-c"), port), false);
        clusters.add(cluster);
        return cluster;
    }

    private static void requirePhoneHomeOff(Config config) {
        assertThat(new HazelcastProperties(config).getBoolean(ClusterProperty.PHONE_HOME_ENABLED))
                .as("a test member must never start with phone-home on").isFalse();
    }

    /** A configured (hence hardened) config for a raw member; {@code tweak} then breaks one thing. */
    private HazelcastInstance rawMember(Consumer<Config> tweak) {
        int port = FreePorts.consecutive(1);
        Config config = PrivacyCluster.configure(ClusterMembership
                .tcpIp(TestMembers.uniqueName("hardening-r"), List.of("127.0.0.1:" + port)).withPort(port)
                .withInterface("127.0.0.1").toConfig());
        tweak.accept(config);
        requirePhoneHomeOff(config);
        HazelcastInstance instance = Hazelcast.newHazelcastInstance(config);
        raw.add(instance);
        return instance;
    }

    private static void refuses(Code code, Runnable action) {
        assertThat(catchThrowable(action::run)).isInstanceOf(PrivacyClusterRefusal.class)
                .satisfies(e -> assertThat(((PrivacyClusterRefusal) e).code()).isEqualTo(code));
    }

    // ---- subsystems and the allowlist, on both embedded paths ----

    private static void assertHardened(HazelcastInstance instance) {
        Config config = instance.getConfig();
        assertThat(config.getJetConfig().isEnabled()).as("jet").isFalse();
        assertThat(config.getUserCodeDeploymentConfig().isEnabled()).as("user-code deployment").isFalse();
        assertThat(config.getNamespacesConfig().isEnabled()).as("user-code namespaces").isFalse();
        assertThat(new HazelcastProperties(config).getBoolean(ClusterProperty.PHONE_HOME_ENABLED))
                .as("phone-home").isFalse();
        // SQL has no switch of its own in 5.7.0 (spike 219, answer 5): it runs on the Jet engine, so with Jet off
        // every query is refused.
        assertThat(catchThrowable(() -> instance.getSql().execute("SELECT 1").close()))
                .as("sql").isNotNull().extracting(e -> e.getClass().getSimpleName()).isEqualTo("JetDisabledException");
        JavaSerializationFilterConfig filter = config.getSerializationConfig().getJavaSerializationFilterConfig();
        assertThat(filter).isNotNull();
        assertThat(filter.isDefaultsDisabled()).isTrue();
        assertThat(filter.getWhitelist().getClasses()).containsExactlyInAnyOrderElementsOf(ALLOWED_CLASSES);
        assertThat(filter.getWhitelist().getPrefixes()).containsExactlyInAnyOrderElementsOf(ALLOWED_PREFIXES);
        assertThat(filter.getWhitelist().getPackages()).isEmpty();
        assertThat(filter.getBlacklist().isEmpty()).isTrue();
    }

    @Test
    void aMemberFromAMembershipHasEverySubsystemOffAndTheExactAllowlist() {
        assertHardened(membershipMember().instance());
    }

    @Test
    void aMemberFromASuppliedConfigHasEverySubsystemOffAndTheExactAllowlist() {
        assertHardened(configMember().instance());
    }

    @Test
    void configureForcesJetUserCodeAndPhoneHomeOffWithoutStartingAnything() {
        Config config = new Config().setClusterName("hardening-force");
        config.getJetConfig().setEnabled(true);
        config.getUserCodeDeploymentConfig().setEnabled(true);
        config.setProperty("hazelcast.phone.home.enabled", "true");
        PrivacyCluster.configure(config);
        assertThat(config.getJetConfig().isEnabled()).isFalse();
        assertThat(config.getUserCodeDeploymentConfig().isEnabled()).isFalse();
        requirePhoneHomeOff(config);
    }

    private static Config filtered(boolean defaultsDisabled, ClassFilter allow) {
        Config config = new Config().setClusterName("hardening-filter");
        config.getSerializationConfig().setJavaSerializationFilterConfig(
                new JavaSerializationFilterConfig().setDefaultsDisabled(defaultsDisabled).setWhitelist(allow));
        return config;
    }

    @Test
    void aSuppliedConfigWhoseFilterAllowsMoreIsRefusedBeforeAnythingStarts() {
        int before = Hazelcast.getAllHazelcastInstances().size();
        Map<String, Config> tooWide = new LinkedHashMap<>();
        tooWide.put("a default-allowing filter", filtered(false, new ClassFilter().setClasses(ALLOWED_CLASSES)
                .setPrefixes(ALLOWED_PREFIXES)));
        tooWide.put("an extra class", filtered(true, new ClassFilter().setClasses(
                Set.of("java.lang.String", "java.util.ArrayList")).setPrefixes(ALLOWED_PREFIXES)));
        tooWide.put("a wide prefix", filtered(true, new ClassFilter().setPrefixes(Set.of("java."))));
        tooWide.put("a package", filtered(true, new ClassFilter().setPackages(Set.of("com.example.other"))));
        tooWide.forEach((what, config) -> {
            Throwable refused = catchThrowable(() -> PrivacyCluster.embedded(config, false));
            assertThat(refused).as(what).isInstanceOf(PrivacyClusterRefusal.class);
            assertThat(((PrivacyClusterRefusal) refused).code()).as(what)
                    .isEqualTo(Code.UNSAFE_HAZELCAST_SERIALIZATION);
            assertThat(refused.getMessage()).as(what).contains("java-serialization-filter")
                    .doesNotContain("ArrayList").doesNotContain("com.example");
        });
        assertThat(Hazelcast.getAllHazelcastInstances()).hasSize(before);
    }

    @Test
    void aSuppliedFilterWithinTheAllowlistIsReplacedByExactlyIt() {
        Config config = filtered(true, new ClassFilter().setClasses(Set.of("java.lang.String")));
        PrivacyCluster.configure(config);
        JavaSerializationFilterConfig filter = config.getSerializationConfig().getJavaSerializationFilterConfig();
        assertThat(filter.isDefaultsDisabled()).isTrue();
        assertThat(filter.getWhitelist().getClasses()).containsExactlyInAnyOrderElementsOf(ALLOWED_CLASSES);
        assertThat(filter.getWhitelist().getPrefixes()).containsExactlyInAnyOrderElementsOf(ALLOWED_PREFIXES);
    }

    // ---- using(instance): checks, never changes ----

    @Test
    void usingAcceptsAnInstanceThatPassesEveryCheck() {
        HazelcastInstance good = rawMember(config -> { });
        PrivacyCluster wrapped = PrivacyCluster.using(good, false);
        assertThat(wrapped.instance()).isSameAs(good);
    }

    private void usingRefusesAndLeavesRunning(String what, Consumer<Config> breakOneThing) {
        HazelcastInstance unsafe = rawMember(breakOneThing);
        Throwable refused = catchThrowable(() -> PrivacyCluster.using(unsafe, false));
        assertThat(refused).as(what).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).as(what).isEqualTo(Code.UNSAFE_HAZELCAST_SERIALIZATION);
        assertThat(unsafe.getLifecycleService().isRunning()).as(what + " is left running").isTrue();
    }

    @Test
    void usingRefusesAnInstanceWithNoFilter() {
        usingRefusesAndLeavesRunning("no filter",
                config -> config.getSerializationConfig().setJavaSerializationFilterConfig(null));
    }

    @Test
    void usingRefusesAFilterThatKeepsTheDefaultsEnabled() {
        usingRefusesAndLeavesRunning("defaults enabled", config -> config.getSerializationConfig()
                .getJavaSerializationFilterConfig().setDefaultsDisabled(false));
    }

    @Test
    void usingRefusesAFilterThatDoesNotAllowDataPrismsOwnClasses() {
        usingRefusesAndLeavesRunning("filter without the Data Prism prefix", config -> config.getSerializationConfig()
                .getJavaSerializationFilterConfig().setWhitelist(new ClassFilter().setClasses(ALLOWED_CLASSES)));
    }

    @Test
    void usingRefusesAnInstanceWithJetEnabled() {
        usingRefusesAndLeavesRunning("jet", config -> config.getJetConfig().setEnabled(true));
    }

    @Test
    void usingRefusesAnInstanceWithUserCodeDeploymentEnabled() {
        usingRefusesAndLeavesRunning("user-code deployment",
                config -> config.getUserCodeDeploymentConfig().setEnabled(true));
    }

    // ---- T12: a keyless client and a disallowed class ----

    private HazelcastInstance clientOf(PrivacyCluster cluster) {
        ClientConfig config = new ClientConfig();
        config.setClusterName(cluster.instance().getConfig().getClusterName());
        config.getNetworkConfig().addAddress("127.0.0.1:" + cluster.instance().getConfig().getNetworkConfig().getPort());
        client = HazelcastClient.newHazelcastClient(config);
        return client;
    }

    private void aDisallowedClassFromAKeylessClientIsRefusedAndTheMemberKeepsServing(PrivacyCluster cluster) {
        HazelcastInstance keyless = clientOf(cluster);
        IMap<String, String> map = keyless.getMap("t12");
        map.put("k", "a");
        // A lambda serialises as java.lang.invoke.SerializedLambda, which the allowlist does not name.
        EntryProcessor<String, String, Boolean> disallowed = entry -> true;
        Throwable refused = catchThrowable(() -> map.executeOnKey("k", disallowed));
        assertThat(refused).isNotNull();
        boolean security = false;
        for (Throwable c = refused; c != null; c = c.getCause()) {
            security |= c instanceof SecurityException || String.valueOf(c.getMessage()).contains("SecurityException");
        }
        assertThat(security).as("a SecurityException in the chain of " + refused).isTrue();
        // Still serving, to the keyless client and to the member itself.
        assertThat(map.get("k")).isEqualTo("a");
        map.put("k", "b");
        IMap<String, String> local = cluster.instance().getMap("t12");
        assertThat(local.get("k")).isEqualTo("b");
    }

    @Test
    @ExpectsFilterRejection
    void aDisallowedClassFromAKeylessClientIsRefusedOnTheMembershipPath() {
        aDisallowedClassFromAKeylessClientIsRefusedAndTheMemberKeepsServing(membershipMember());
    }

    @Test
    @ExpectsFilterRejection
    void aDisallowedClassFromAKeylessClientIsRefusedOnTheConfigPath() {
        aDisallowedClassFromAKeylessClientIsRefusedAndTheMemberKeepsServing(configMember());
    }

    /** T12 (b): every entry processor and merge policy Data Prism sends between members passes the filter. */
    @Test
    void everyDataPrismProcessorAndMergePolicyAndTheTypesTheyCarryPassTheConfiguredFilter() throws Exception {
        Config config = PrivacyCluster.configure(new Config().setClusterName("hardening-t12"));
        ClassFilter allow = config.getSerializationConfig().getJavaSerializationFilterConfig().getWhitelist();
        java.nio.file.Path classes = java.nio.file.Path.of(PrivacyCluster.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        List<Class<?>> sent = new ArrayList<>();
        try (var files = java.nio.file.Files.walk(classes)) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator) {
                String name = classes.relativize(file).toString();
                if (name.endsWith(".class") && name.startsWith("io/github/aindriub/dataprism/hazelcast/")) {
                    Class<?> type = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false,
                            PrivacyCluster.class.getClassLoader());
                    if (EntryProcessor.class.isAssignableFrom(type)
                            || com.hazelcast.spi.merge.SplitBrainMergePolicy.class.isAssignableFrom(type)) {
                        sent.add(type);
                    }
                }
            }
        }
        assertThat(sent).as("the processors and merge policies found").hasSizeGreaterThanOrEqualTo(7);
        for (Class<?> type : sent) {
            assertThat(isAllowed(allow, type.getName())).as(type.getName()).isTrue();
            for (var field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                // Instant is written by java.time.Ser.
                String carried = field.getType() == java.time.Instant.class ? "java.time.Ser"
                        : field.getType().getName();
                assertThat(isAllowed(allow, carried)).as(type.getName() + "." + field.getName()).isTrue();
            }
        }
    }

    private static boolean isAllowed(ClassFilter allow, String name) {
        return allow.getClasses().contains(name) || allow.getPrefixes().stream().anyMatch(name::startsWith);
    }

    // ---- Management Center protocol messages (spike 219, Q3 and Q13) ----

    /** Sends a raw Management Center message through the client's own connection. "allowed" or the failure's name. */
    private static String send(HazelcastInstance client, ClientMessage message) {
        var impl = ((com.hazelcast.client.impl.clientside.HazelcastClientProxy) client).client;
        try {
            new com.hazelcast.client.impl.spi.impl.ClientInvocation(impl, message, null).invoke()
                    .get(20, TimeUnit.SECONDS);
            return "allowed";
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause().getClass().getSimpleName();
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    /** The message types the trusted-interface list is observed to block; a regression to an empty list fails them. */
    private static Map<String, ClientMessage> managementMessages() {
        Map<String, ClientMessage> messages = new LinkedHashMap<>();
        messages.put("change cluster state", MCChangeClusterStateCodec.encodeRequest(ClusterState.FROZEN.getId()));
        messages.put("shut down cluster", MCShutdownClusterCodec.encodeRequest());
        messages.put("shut down member", MCShutdownMemberCodec.encodeRequest());
        messages.put("run gc", MCRunGcCodec.encodeRequest());
        messages.put("get thread dump", MCGetThreadDumpCodec.encodeRequest(false));
        messages.put("get member config", MCGetMemberConfigCodec.encodeRequest());
        messages.put("get system properties", MCGetSystemPropertiesCodec.encodeRequest());
        messages.put("get timed member state", MCGetTimedMemberStateCodec.encodeRequest());
        messages.put("reload config", MCReloadConfigCodec.encodeRequest());
        messages.put("promote lite member", MCPromoteLiteMemberCodec.encodeRequest());
        messages.put("get cluster metadata", MCGetClusterMetadataCodec.encodeRequest());
        messages.put("run script", MCRunScriptCodec.encodeRequest("js", "1"));
        messages.put("run console command", MCRunConsoleCommandCodec.encodeRequest("ns", "help"));
        messages.put("change cluster version", MCChangeClusterVersionCodec.encodeRequest((byte) 5, (byte) 7));
        return messages;
    }

    private void aKeylessClientIsRefusedEveryManagementMessage(PrivacyCluster cluster) {
        HazelcastInstance keyless = clientOf(cluster);
        // Stops at the first message that is accepted: one that was, may have stopped the member.
        managementMessages().forEach((name, message) -> assertThat(send(keyless, message)).as(name)
                .isEqualTo("AccessControlException"));
        assertThat(cluster.instance().getCluster().getClusterState()).isEqualTo(ClusterState.ACTIVE);
        assertThat(cluster.instance().getLifecycleService().isRunning()).isTrue();
        keyless.getMap("mc").put("k", "v");
        assertThat(keyless.getMap("mc").get("k")).isEqualTo("v");
    }

    @Test
    void aKeylessClientIsRefusedEveryManagementMessageOnTheMembershipPath() {
        aKeylessClientIsRefusedEveryManagementMessage(membershipMember());
    }

    @Test
    void aKeylessClientIsRefusedEveryManagementMessageOnTheConfigPath() {
        aKeylessClientIsRefusedEveryManagementMessage(configMember());
    }

    @Test
    void theTrustedInterfaceListIsNonEmptyAndMatchesNoClientAddress() {
        Set<String> trusted = membershipMember().instance().getConfig().getManagementCenterConfig()
                .getTrustedInterfaces();
        assertThat(trusted).isNotEmpty().doesNotContain("127.0.0.1", "0.0.0.0", "*.*.*.*");
        assertThat(trusted).allSatisfy(entry -> assertThat(entry).doesNotContain("*").doesNotContain("-"));
    }

    @Test
    void aSuppliedTrustedListThatMatchesTheClientIsReplaced() {
        Config config = new Config().setClusterName("hardening-mc");
        config.getManagementCenterConfig().setTrustedInterfaces(Set.of("*.*.*.*"));
        PrivacyCluster.configure(config);
        assertThat(config.getManagementCenterConfig().getTrustedInterfaces()).isNotEmpty()
                .doesNotContain("*.*.*.*");
    }

    @Test
    void anEmptyTrustedListIsUnsafeBecauseAClientFreezesTheCluster() {
        // The premise of the two tests above: with Hazelcast's default (empty means unrestricted) a keyless client
        // freezes the cluster. A build that regressed to an empty list would fail the refusal tests.
        HazelcastInstance unrestricted = rawMember(config -> config.getManagementCenterConfig()
                .setTrustedInterfaces(Set.of()));
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setClusterName(unrestricted.getConfig().getClusterName());
        clientConfig.getNetworkConfig().addAddress("127.0.0.1:" + unrestricted.getConfig().getNetworkConfig().getPort());
        client = HazelcastClient.newHazelcastClient(clientConfig);
        assertThat(send(client, MCChangeClusterStateCodec.encodeRequest(ClusterState.FROZEN.getId())))
                .isEqualTo("allowed");
        assertThat(await(20, () -> unrestricted.getCluster().getClusterState() == ClusterState.FROZEN)).isTrue();
    }

    private static boolean await(long seconds, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
        return condition.getAsBoolean();
    }

    @Test
    void embeddedStillRefusesAnInvalidMinimum() {
        assertThatThrownBy(() -> PrivacyCluster.embedded(new Config().setClusterName("x"), false, OptionalInt.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- REST, memcache, global serializer, trusted interfaces and phone-home ----

    @Test
    void bothEmbeddedPathsForceTheRestApiAndMemcacheOff() {
        for (PrivacyCluster cluster : List.of(membershipMember(), configMember())) {
            var network = cluster.instance().getConfig().getNetworkConfig();
            assertThat(network.getRestApiConfig().isEnabled()).isFalse();
            assertThat(network.getMemcacheProtocolConfig().isEnabled()).isFalse();
        }
        Config supplied = new Config().setClusterName("hardening-rest");
        supplied.getNetworkConfig().getRestApiConfig().setEnabled(true)
                .enableGroups(com.hazelcast.config.RestEndpointGroup.CLUSTER_WRITE);
        supplied.getNetworkConfig().getMemcacheProtocolConfig().setEnabled(true);
        PrivacyCluster.configure(supplied);
        assertThat(supplied.getNetworkConfig().getRestApiConfig().isEnabled()).isFalse();
        assertThat(supplied.getNetworkConfig().getMemcacheProtocolConfig().isEnabled()).isFalse();
    }

    private void usingRefusesAnUnsafeSetting(String what, Consumer<Config> breakOneThing) {
        Config config = PrivacyCluster.configure(ClusterMembership
                .tcpIp("hardening-proxy", List.of("127.0.0.1:1")).withPort(1).withInterface("127.0.0.1").toConfig());
        breakOneThing.accept(config);
        // A stand-in that only reports its config, so that a setting that must not be started can still be judged.
        HazelcastInstance stand = (HazelcastInstance) java.lang.reflect.Proxy.newProxyInstance(
                HazelcastInstance.class.getClassLoader(), new Class<?>[]{HazelcastInstance.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConfig")) {
                        return config;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Throwable refused = catchThrowable(() -> PrivacyCluster.using(stand, false));
        assertThat(refused).as(what).isInstanceOf(PrivacyClusterRefusal.class);
        assertThat(((PrivacyClusterRefusal) refused).code()).as(what).isEqualTo(Code.UNSAFE_HAZELCAST_SERIALIZATION);
    }

    @Test
    void usingRefusesMemcacheAndAnyRestGroupBeyondHealthCheck() {
        usingRefusesAnUnsafeSetting("memcache",
                config -> config.getNetworkConfig().getMemcacheProtocolConfig().setEnabled(true));
        usingRefusesAnUnsafeSetting("rest cluster write", config -> config.getNetworkConfig().getRestApiConfig()
                .setEnabled(true).enableGroups(com.hazelcast.config.RestEndpointGroup.CLUSTER_WRITE));
        usingRefusesAnUnsafeSetting("rest data", config -> config.getNetworkConfig().getRestApiConfig()
                .setEnabled(true).enableGroups(com.hazelcast.config.RestEndpointGroup.DATA));
    }

    @Test
    void usingAcceptsRestWithOnlyTheHealthCheckGroup() {
        HazelcastInstance healthOnly = rawMember(config -> config.getNetworkConfig().getRestApiConfig()
                .setEnabled(true).disableAllGroups().enableGroups(com.hazelcast.config.RestEndpointGroup.HEALTH_CHECK));
        assertThat(PrivacyCluster.using(healthOnly, false).instance()).isSameAs(healthOnly);
    }

    @Test
    void usingRefusesUnrestrictedManagementPhoneHomeAndAGlobalSerializerOverride() {
        usingRefusesAnUnsafeSetting("empty trusted interfaces",
                config -> config.getManagementCenterConfig().setTrustedInterfaces(Set.of()));
        usingRefusesAnUnsafeSetting("phone-home on",
                config -> config.setProperty("hazelcast.phone.home.enabled", "true"));
        usingRefusesAnUnsafeSetting("global serializer override", config -> config.getSerializationConfig()
                .setGlobalSerializerConfig(new com.hazelcast.config.GlobalSerializerConfig()
                        .setClassName("example.Serializer").setOverrideJavaSerialization(true)));
    }

    @Test
    void aNonEmptyTrustedListOnASuppliedInstanceIsTheIntegratorsToJudge() {
        HazelcastInstance own = rawMember(config -> config.getManagementCenterConfig()
                .setTrustedInterfaces(Set.of("10.9.9.9")));
        assertThat(PrivacyCluster.using(own, false).instance()).isSameAs(own);
    }

    @Test
    void embeddedRefusesAGlobalSerializerThatOverridesJavaSerialization() {
        Config config = new Config().setClusterName("hardening-global");
        config.getSerializationConfig().setGlobalSerializerConfig(new com.hazelcast.config.GlobalSerializerConfig()
                .setClassName("example.Serializer").setOverrideJavaSerialization(true));
        refuses(Code.UNSAFE_HAZELCAST_SERIALIZATION, () -> PrivacyCluster.embedded(config, false));
    }

    // ---- the filter a supplied instance must carry ----

    @Test
    void usingRefusesAnAdditionalPrefixOrPackageAndAcceptsAnAdditionalNamedClass() {
        for (String prefix : List.of("", "java.", "org.")) {
            usingRefusesAnUnsafeSetting("prefix '" + prefix + "'", config -> config.getSerializationConfig()
                    .getJavaSerializationFilterConfig().getWhitelist().addPrefixes(prefix));
        }
        usingRefusesAnUnsafeSetting("package", config -> config.getSerializationConfig()
                .getJavaSerializationFilterConfig().getWhitelist().addPackages("com.example.other"));
        HazelcastInstance shared = rawMember(config -> config.getSerializationConfig()
                .getJavaSerializationFilterConfig().getWhitelist().addClasses("com.example.other.Own"));
        assertThat(PrivacyCluster.using(shared, false).instance()).isSameAs(shared);
    }

    @Test
    void theRequiredFilterIsFreshOnEachCallAndEqualsThePin() {
        JavaSerializationFilterConfig first = PrivacyCluster.requiredSerializationFilter();
        assertThat(first.isDefaultsDisabled()).isTrue();
        assertThat(first.getWhitelist().getClasses()).containsExactlyInAnyOrderElementsOf(ALLOWED_CLASSES);
        assertThat(first.getWhitelist().getPrefixes()).containsExactlyInAnyOrderElementsOf(ALLOWED_PREFIXES);
        first.getWhitelist().addPrefixes("java.");
        assertThat(PrivacyCluster.requiredSerializationFilter().getWhitelist().getPrefixes())
                .containsExactlyInAnyOrderElementsOf(ALLOWED_PREFIXES);
    }

    // ---- an error thrown by a processor comes back to its caller ----

    /** Reads the stored value as a count, as the budget processor does. */
    private record ExpectsAnInteger() implements EntryProcessor<String, Object, Boolean> {
        @Override
        public Boolean process(Map.Entry<String, Object> entry) {
            int count = (Integer) entry.getValue();
            return count > 0;
        }
    }

    @Test
    void aForeignValueUnderABudgetKeyIsRefusedAndTheOwnersErrorReachesTheCaller() {
        PrivacyCluster[] pair = TestMembers.pair(TestMembers.uniqueName("hardening-cce"), OptionalInt.empty());
        clusters.add(pair[0]);
        clusters.add(pair[1]);
        HazelcastInstance owner = pair[1].instance();
        String key = null;
        String subject = null;
        for (int i = 0; i < 10_000 && key == null; i++) {
            String candidate = ScopeKeys.budget("scope-a", "subject-" + i);
            if (pair[0].instance().getPartitionService().getPartition(candidate).getOwner().equals(
                    owner.getCluster().getLocalMember())) {
                key = candidate;
                subject = "subject-" + i;
            }
        }
        assertThat(key).isNotNull();
        String finalSubject = subject;
        // Something other than an Integer under the key, as a hostile or confused writer could leave.
        pair[0].instance().<String, Object>getMap(PrivacyCluster.BUDGET_MAP).put(key, "not-a-count");
        boolean admitted = new HazelcastScopeBudget(pair[0]).tryRead("scope-a", subject, 5);
        assertThat(admitted).as("a value that is not a count refuses the read").isFalse();
        // The outcome type proves the owner's error reply was not lost: the caller sees the owner's exception,
        // never an OperationTimeoutException.
        IMap<String, Object> counts = pair[0].instance().getMap(PrivacyCluster.BUDGET_MAP);
        Throwable thrown = catchThrowable(() -> counts.executeOnKey(ScopeKeys.budget("scope-a", finalSubject),
                new ExpectsAnInteger()));
        assertThat(thrown).isNotNull();
        StringBuilder chain = new StringBuilder();
        for (Throwable c = thrown; c != null; c = c.getCause()) {
            chain.append(c.getClass().getName()).append(' ');
        }
        assertThat(chain.toString()).contains("ClassCastException").doesNotContain("OperationTimeoutException");
    }
}
