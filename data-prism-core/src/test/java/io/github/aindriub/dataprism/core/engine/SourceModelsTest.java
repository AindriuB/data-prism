package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A source model is a record all the way down; any other user class is refused with one stable code. */
class SourceModelsTest {

    public static class Bean {
        public String secret = "value-that-must-not-appear";
    }

    public enum Tier { GOLD }

    public record Inner(String name, Tier tier) {
    }

    public record Allowed(Inner inner, List<Inner> list, Map<String, Optional<Inner>> map, Inner[] array,
                          Instant at, UUID id, JsonNode node, Object any, Runnable iface) {
    }

    public record WithBean(String name, Bean bean) {
    }

    public record NestedBean(Inner inner, WithBean deeper) {
    }

    public record InList(List<Bean> beans) {
    }

    public record InMap(Map<String, Bean> beans) {
    }

    public record InOptional(Optional<Bean> bean) {
    }

    public record InArray(Bean[] beans) {
    }

    public record Untyped(Object any) {
    }

    public record Wild(List<? extends Bean> beans) {
    }

    private static void assertRefused(Runnable action) {
        assertRefused(action, "Bean");
    }

    private static void assertRefused(Runnable action, String name) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(PrivacyRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("SOURCE_MODEL_NOT_A_RECORD");
                    assertThat(e.getMessage()).contains(name).doesNotContain("value-that-must-not-appear");
                });
    }

    @Test
    void theCodeIsAValidRefusalToken() {
        assertThat(io.github.aindriub.dataprism.core.refusal.RefusalCodes.sanitise(SourceModels.CODE))
                .isEqualTo(SourceModels.CODE);
    }

    @Test
    void aBeanAtTopLevelIsRefusedAtStartupAndAtRuntime() {
        assertRefused(() -> SourceModels.require(Bean.class));
        assertRefused(() -> SourceTree.of(new Bean()));
    }

    @Test
    void aTopLevelTypeThatIsNotARecordIsRefusedAtStartupJdkTypesIncluded() {
        assertRefused(() -> SourceModels.require(String.class), "String");
        assertRefused(() -> SourceModels.require(Tier.class), "Tier");
    }

    @Test
    void aBeanInARecordComponentIsRefusedAtStartup() {
        assertRefused(() -> SourceModels.require(WithBean.class));
        assertRefused(() -> SourceModels.require(NestedBean.class));
    }

    @Test
    void aBeanInsideACollectionMapOptionalArrayOrWildcardIsRefusedAtStartup() {
        assertRefused(() -> SourceModels.require(InList.class));
        assertRefused(() -> SourceModels.require(InMap.class));
        assertRefused(() -> SourceModels.require(InOptional.class));
        assertRefused(() -> SourceModels.require(InArray.class));
        assertRefused(() -> SourceModels.require(Wild.class));
    }

    @Test
    void aBeanIsRefusedAtRuntimeWhereverItSits() {
        assertRefused(() -> SourceTree.of(new WithBean("n", new Bean())));
        assertRefused(() -> SourceTree.of(new InList(List.of(new Bean()))));
        assertRefused(() -> SourceTree.of(new InMap(Map.of("k", new Bean()))));
        assertRefused(() -> SourceTree.of(new InOptional(Optional.of(new Bean()))));
        assertRefused(() -> SourceTree.of(new InArray(new Bean[]{new Bean()})));
    }

    @Test
    void aBeanBehindAnObjectTypedComponentIsPassedAtStartupButRefusedAtRuntime() {
        assertThatCode(() -> SourceModels.require(Untyped.class)).doesNotThrowAnyException();
        assertRefused(() -> SourceTree.of(new Untyped(new Bean())));
        assertRefused(() -> SourceTree.of(new Untyped(List.of(Map.of("k", new Bean())))));
    }

    @Test
    void aTopLevelNonRecordOfAnyKindIsRefused() {
        assertThatThrownBy(() -> SourceTree.of(Map.of("k", "v"))).isInstanceOf(PrivacyRefusedException.class);
        assertThatThrownBy(() -> SourceTree.of(Tier.GOLD)).isInstanceOf(PrivacyRefusedException.class);
    }

    @Test
    void recordsEnumsJdkTypesAndTreeNodesAreAllowed() {
        assertThatCode(() -> SourceModels.require(Allowed.class)).doesNotThrowAnyException();
        ObjectNode node = SourceTree.newObject();
        node.put("a", 1);
        Inner inner = new Inner("n", Tier.GOLD);
        JsonNode tree = SourceTree.of(new Allowed(inner, List.of(inner), Map.of("k", Optional.of(inner)),
                new Inner[]{inner}, Instant.parse("2026-10-08T12:00:00Z"), UUID.fromString(
                "00000000-0000-0000-0000-000000000001"), node, inner, null));
        assertThat(tree.get("inner").get("tier").asString()).isEqualTo("GOLD");
        assertThat(tree.get("node").get("a").intValue()).isEqualTo(1);
        assertThat(tree.get("any").get("name").asString()).isEqualTo("n");
    }

    @Test
    void aRecursiveRecordTypeTerminates() {
        assertThatCode(() -> SourceModels.require(Tree.class)).doesNotThrowAnyException();
    }

    public record Tree(String name, List<Tree> children) {
    }
}
