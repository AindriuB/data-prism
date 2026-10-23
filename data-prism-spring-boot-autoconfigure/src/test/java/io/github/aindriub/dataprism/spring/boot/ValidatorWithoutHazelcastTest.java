package io.github.aindriub.dataprism.spring.boot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Task 222: {@code data-prism-hazelcast} is optional, so the property validator must load and pass without it.
 * The validator is loaded by the application loader, not by the context, so the hiding is done here by a loader
 * that defines this module's own classes itself and refuses every Hazelcast and Data Prism Hazelcast type.
 */
class ValidatorWithoutHazelcastTest {

    private static final String OWN = "io.github.aindriub.dataprism.spring.boot.";

    /** Child-first for this module's classes; the Hazelcast types do not exist. */
    private static final class HidingLoader extends ClassLoader {
        HidingLoader() {
            super(ValidatorWithoutHazelcastTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.hazelcast.") || name.startsWith("io.github.aindriub.dataprism.hazelcast.")) {
                throw new ClassNotFoundException(name);
            }
            if (name.startsWith(OWN) && !name.startsWith(OWN + "ClusterKeyConfigurationTest")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        String resource = name.replace('.', '/') + ".class";
                        try (InputStream in = getParent().getResourceAsStream(resource)) {
                            if (in == null) {
                                return super.loadClass(name, resolve);
                            }
                            byte[] bytes = in.readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    private static void validate(String topology, String joinMode) throws Exception {
        ClassLoader loader = new HidingLoader();
        assertThatCode(() -> loader.loadClass("com.hazelcast.core.Hazelcast"))
                .isInstanceOf(ClassNotFoundException.class);
        Class<?> propertiesType = loader.loadClass(OWN + "DataPrismProperties");
        Class<?> validatorType = loader.loadClass(OWN + "validation.DataPrismPropertiesValidator");
        assertThat(validatorType.getClassLoader()).isSameAs(loader);
        Object properties = propertiesType.getConstructor().newInstance();
        Object hazelcast = propertiesType.getMethod("getHazelcast").invoke(properties);
        hazelcast.getClass().getMethod("setTopology", String.class).invoke(hazelcast, topology);
        if (joinMode != null) {
            Object join = hazelcast.getClass().getMethod("getJoin").invoke(hazelcast);
            join.getClass().getMethod("setMode", String.class).invoke(join, joinMode);
            hazelcast.getClass().getMethod("setClusterName", String.class).invoke(hazelcast, "absent-test");
            Object member = hazelcast.getClass().getMethod("getMember").invoke(hazelcast);
            member.getClass().getMethod("setInterface", String.class).invoke(member, "10.0.*.*");
            Object kube = join.getClass().getMethod("getKubernetes").invoke(join);
            kube.getClass().getMethod("setNamespace", String.class).invoke(kube, "ns");
            kube.getClass().getMethod("setServiceName", String.class).invoke(kube, "svc");
            Object security = hazelcast.getClass().getMethod("getSecurity").invoke(hazelcast);
            Object cluster = security.getClass().getMethod("getClusterKey").invoke(security);
            cluster.getClass().getMethod("setKeyId", String.class).invoke(cluster, "c1");
            cluster.getClass().getMethod("setEnvironmentVariable", String.class).invoke(cluster, "REF_C");
            Object name = security.getClass().getMethod("getNameKey").invoke(security);
            name.getClass().getMethod("setKeyId", String.class).invoke(name, "n1");
            name.getClass().getMethod("setEnvironmentVariable", String.class).invoke(name, "REF_N");
        }
        Object validator = validatorType.getConstructor(propertiesType).newInstance(properties);
        validatorType.getMethod("validateCluster", boolean.class, Integer.class, Integer.class)
                .invoke(validator, false, null, null);
    }

    @Test
    void single_node_validates_with_the_hazelcast_types_absent() {
        assertThatCode(() -> validate("single-node", null)).doesNotThrowAnyException();
    }

    @Test
    void an_embedded_join_is_left_to_the_later_refusal_rather_than_failing_to_link() {
        assertThatCode(() -> validate("embedded", "kubernetes")).doesNotThrowAnyException();
    }
}
