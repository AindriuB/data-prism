package io.github.aindriub.dataprism.core.engine;

import io.github.aindriub.dataprism.annotations.DataClassification;
import io.github.aindriub.dataprism.annotations.PrivacyAction;
import io.github.aindriub.dataprism.annotations.PrivacyNamespace;
import io.github.aindriub.dataprism.annotations.SensitiveData;
import io.github.aindriub.dataprism.core.refusal.PrivacyRefusedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.StringNode;

import java.time.Duration;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** The node cap on one walk of one source value (task 291). All values are invented. */
class SourceTreeNodeCapTest {

    private static final int MAX = SourceRecordWalker.MAX_NODES;
    private static final Duration GUARD = Duration.ofSeconds(60);
    private static final String ITEM = "item@example.invalid";
    private static final String MESSAGE = "the source value has more nodes than the engine reads";

    record Items(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) List<String> items,
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) List<String> more) {
    }

    record Rows(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) List<List<String>> rows) {
    }

    record One(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) List<String> items) {
    }

    record Mapped(
            @SensitiveData(classifications = DataClassification.CONTACT, namespace = PrivacyNamespace.EMAIL,
                    suggestedAction = PrivacyAction.REDACT) Map<String, String> entries) {
    }

    record Tree(JsonNode extra) {
    }

    /** A list that yields element(i) up to size, counting its iterator() and next() calls. */
    static final class Counting extends AbstractList<String> {
        final AtomicInteger iterators = new AtomicInteger();
        final AtomicInteger nexts = new AtomicInteger();
        private final int size;
        private final IntFunction<String> element;

        Counting(int size, IntFunction<String> element) {
            this.size = size;
            this.element = element;
        }

        static Counting endless() {
            return new Counting(Integer.MAX_VALUE, i -> ITEM);
        }

        @Override
        public String get(int index) {
            return element.apply(index);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public Iterator<String> iterator() {
            iterators.incrementAndGet();
            return new Iterator<>() {
                private int at;

                @Override
                public boolean hasNext() {
                    return at < size;
                }

                @Override
                public String next() {
                    nexts.incrementAndGet();
                    return element.apply(at++);
                }
            };
        }

        void reset() {
            iterators.set(0);
            nexts.set(0);
        }
    }

    private static Set<String> prohibited(Object source) {
        return assertTimeoutPreemptively(GUARD,
                () -> SourceValues.prohibited(source, new DefaultFieldMetadataResolver()));
    }

    private static PrivacyRefusedException refused(Object source) {
        return assertTimeoutPreemptively(GUARD, () -> {
            try {
                SourceTree.of(source);
            } catch (PrivacyRefusedException e) {
                return e;
            }
            throw new AssertionError("expected a refusal");
        });
    }

    private static void assertTooLarge(PrivacyRefusedException e) {
        assertThat(e.code()).isEqualTo("SOURCE_TREE_TOO_LARGE").isEqualTo(SourceTree.SOURCE_TREE_TOO_LARGE);
        assertThat(e.path()).isEqualTo("$");
        assertThat(e.getMessage()).contains(MESSAGE);
    }

    private static One one(Counting list) {
        return new One(Collections.unmodifiableList(list));
    }

    @Test
    @DisplayName("an endless list is refused by of and stops the lenient walk, with next() bounded")
    void endlessList() {
        Counting endless = Counting.endless();
        Counting endless2 = Counting.endless();
        Items value = new Items(Collections.unmodifiableList(endless), Collections.unmodifiableList(endless2));

        assertTooLarge(refused(value));
        assertThat(endless.nexts.get()).isLessThanOrEqualTo(MAX);
        assertThat(endless2.iterators.get()).isZero();

        endless.reset();
        endless2.reset();
        Set<String> set = prohibited(value);
        assertThat(set).contains(ITEM);
        assertThat(endless2.iterators.get()).isZero();
        assertThat(endless.nexts.get() + endless2.nexts.get()).isLessThanOrEqualTo(MAX);
    }

    @Test
    @DisplayName("the lenient walk stops whole: it does not return to the outer iteration")
    void lenientStopsWhole() {
        AtomicInteger outerHasNext = new AtomicInteger();
        List<List<String>> parts = List.of(
                Collections.unmodifiableList(Counting.endless()), List.of("after@example.invalid"));
        List<List<String>> outer = new AbstractList<>() {
            @Override
            public List<String> get(int index) {
                return parts.get(index);
            }

            @Override
            public int size() {
                return 2;
            }

            @Override
            public Iterator<List<String>> iterator() {
                Iterator<List<String>> inner = parts.iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        outerHasNext.incrementAndGet();
                        return inner.hasNext();
                    }

                    @Override
                    public List<String> next() {
                        return inner.next();
                    }
                };
            }
        };
        Rows value = new Rows(Collections.unmodifiableList(outer));

        Set<String> set = prohibited(value);
        assertThat(outerHasNext.get()).isEqualTo(1);
        assertThat(set).contains(ITEM).doesNotContain("after@example.invalid");

        outerHasNext.set(0);
        assertTooLarge(refused(value));
        assertThat(outerHasNext.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("exactly MAX_NODES nodes passes; one more element is refused")
    void boundary() {
        // root + the list + one node per element
        int exact = MAX - 2;
        Counting atCap = new Counting(exact, i -> "item-" + i + "@example.invalid");
        JsonNode tree = SourceTree.of(one(atCap));
        assertThat(tree.get("items")).hasSize(exact);
        assertThat(atCap.nexts.get()).isLessThanOrEqualTo(MAX);

        atCap.reset();
        assertThat(prohibited(one(atCap))).contains("item-" + (exact - 1) + "@example.invalid");
        assertThat(atCap.nexts.get()).isLessThanOrEqualTo(MAX);

        Counting over = new Counting(exact + 1, i -> "item-" + i + "@example.invalid");
        assertTooLarge(refused(one(over)));
        assertThat(over.nexts.get()).isLessThanOrEqualTo(MAX);
        over.reset();
        Set<String> set = prohibited(one(over));
        assertThat(set).contains("item-" + (exact - 1) + "@example.invalid")
                .doesNotContain("item-" + exact + "@example.invalid");
        assertThat(over.nexts.get()).isLessThanOrEqualTo(MAX);
    }

    /** A map whose entry iterator never ends. */
    static final class EndlessMap extends AbstractMap<String, String> {
        final AtomicInteger nexts = new AtomicInteger();

        @Override
        public Set<Entry<String, String>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, String>> iterator() {
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return true;
                        }

                        @Override
                        public Entry<String, String> next() {
                            nexts.incrementAndGet();
                            return Map.entry("k", "map@example.invalid");
                        }
                    };
                }

                @Override
                public int size() {
                    return Integer.MAX_VALUE;
                }
            };
        }
    }

    @Test
    @DisplayName("an endless map is refused by of and stops the lenient walk")
    void endlessMap() {
        EndlessMap endless = new EndlessMap();
        Mapped value = new Mapped(Collections.unmodifiableMap(endless));
        assertTooLarge(refused(value));
        assertThat(endless.nexts.get()).isLessThanOrEqualTo(MAX);
        endless.nexts.set(0);
        assertThat(prohibited(value)).contains("map@example.invalid");
        assertThat(endless.nexts.get()).isLessThanOrEqualTo(MAX);
    }

    @Test
    @DisplayName("an endless ArrayNode is refused by of and stops the lenient walk")
    void endlessArrayNode() {
        AtomicInteger nexts = new AtomicInteger();
        List<JsonNode> endless = new AbstractList<>() {
            @Override
            public JsonNode get(int index) {
                return StringNode.valueOf("node@example.invalid");
            }

            @Override
            public int size() {
                return Integer.MAX_VALUE;
            }

            @Override
            public Iterator<JsonNode> iterator() {
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public JsonNode next() {
                        nexts.incrementAndGet();
                        return StringNode.valueOf("node@example.invalid");
                    }
                };
            }
        };
        Tree value = new Tree(new ArrayNode(JsonNodeFactory.instance, endless));
        assertTooLarge(refused(value));
        assertThat(nexts.get()).isLessThanOrEqualTo(MAX);
        nexts.set(0);
        prohibited(value);
        assertThat(nexts.get()).isLessThanOrEqualTo(MAX);
    }

    @Test
    @DisplayName("JsonNode content counts toward the cap")
    void jsonNodeContentCounts() {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < MAX; i++) {
            array.add(IntNode.valueOf(i));
        }
        Tree value = new Tree(array);
        assertTooLarge(refused(value));
        assertThat(prohibited(value)).isNotNull();
    }

    @Test
    @DisplayName("the cap inside a collection element is not turned into a read failure")
    void notAReadFailure() {
        PrivacyRefusedException e = refused(one(Counting.endless()));
        assertThat(e.code()).isNotEqualTo("SOURCE_VALUE_READ_FAILED").isEqualTo("SOURCE_TREE_TOO_LARGE");
    }

    @Test
    @DisplayName("a small value is unaffected")
    void smallValueUnaffected() {
        Counting small = new Counting(3, i -> "s" + i + "@example.invalid");
        assertThat(SourceTree.of(one(small)).get("items")).hasSize(3);
    }
}
