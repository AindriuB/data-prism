package io.github.aindriub.dataprism.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two readers of {@code SourceTree} (task 288). Source values are turned into trees by the engine's
 * own walker; Jackson writes only JDK scalar leaves, through one guarded reader, from one method. The
 * inspecting reader only looks serializers up. So the scrub path has no unguarded way to write a value.
 *
 * <p>Each rule is also run against a negative fixture for each forbidden call, so it cannot go blind to one.
 * This is its own file because the module's main architecture rules live in {@link ArchitectureTest}.
 */
class SourceTreeReadersArchitectureTest {

    private static final String SOURCE_TREE = "io.github.aindriub.dataprism.core.engine.SourceTree";
    private static final String WALKER = "io.github.aindriub.dataprism.core.engine.SourceRecordWalker";

    /** The methods that may read the inspecting reader's field (the describer's four go with task 289). */
    private static final Set<String> INSPECTING_READERS = Set.of("refuseIfReadByGetters", "writtenAsStructure",
            "referenceTree", "newObject", "newArray", "text",
            "readsAsPlainBean", "overridesSerialization", "readsAsPlainScalar", "overrides", "plainHolder",
            "plainBean", "contextual");

    private static JavaClasses CLASSES;

    @BeforeAll
    static void importWholeGraph() throws IOException {
        Path repositoryRoot = ModuleGraph.repositoryRoot();
        List<String> modules = ModuleGraph.declaredModules(repositoryRoot.resolve("pom.xml"));
        CLASSES = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPaths(ModuleGraph.mainClassesDirectories(repositoryRoot, modules));
    }

    // ---- the rules -----------------------------------------------------------------------------

    /** Callers (other than those allowed) of {@code owner#method}. */
    static List<String> callsTo(JavaClasses classes, String owner, String method, Predicate<JavaClass> allowed) {
        List<String> violations = new ArrayList<>();
        for (JavaClass caller : classes) {
            if (allowed.test(caller)) {
                continue;
            }
            for (JavaMethodCall call : caller.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().getFullName().equals(owner) && call.getTarget().getName().equals(method)) {
                    violations.add(call.getDescription());
                }
            }
        }
        return violations;
    }

    /** Reads of the static field {@code owner#field} from any method of {@code owner} not named in {@code allowed}. */
    static List<String> fieldReads(JavaClasses classes, String owner, String field, Set<String> allowed) {
        List<String> violations = new ArrayList<>();
        for (JavaClass c : classes) {
            for (JavaFieldAccess access : c.getFieldAccessesFromSelf()) {
                if (access.getAccessType() == JavaFieldAccess.AccessType.GET
                        && access.getTargetOwner().getFullName().equals(owner)
                        && access.getTarget().getName().equals(field)
                        && !allowed.contains(access.getOrigin().getName())) {
                    violations.add(access.getDescription());
                }
            }
        }
        return violations;
    }

    private static boolean isSerialising(JavaMethodCall call) {
        String name = call.getTarget().getName();
        JavaClass owner = call.getTargetOwner();
        if (owner.isAssignableTo(ObjectWriter.class)) {
            return name.startsWith("write") || name.startsWith("serialize");
        }
        if (owner.isAssignableTo(ObjectMapper.class)) {
            return name.equals("valueToTree") || name.equals("convertValue") || name.startsWith("writeValue")
                    || name.startsWith("writeTree");
        }
        if (owner.isAssignableTo(SerializationContext.class)) {
            return name.startsWith("serialize") || name.startsWith("writeValue")
                    || name.startsWith("defaultSerialize");
        }
        return false;
    }

    /** Serialising calls from the named classes (and their nested classes), except from the allowed methods. */
    static List<String> serialisingCalls(JavaClasses classes, Set<String> classNames, Set<String> allowedMethods) {
        List<String> violations = new ArrayList<>();
        for (JavaClass c : classes) {
            String outer = c.getFullName().contains("$") ? c.getFullName().substring(0, c.getFullName().indexOf('$'))
                    : c.getFullName();
            if (!classNames.contains(outer) && !classNames.contains(c.getFullName())) {
                continue;
            }
            for (JavaMethodCall call : c.getMethodCallsFromSelf()) {
                if (isSerialising(call) && !allowedMethods.contains(call.getOrigin().getName())) {
                    violations.add(call.getDescription());
                }
            }
        }
        return violations;
    }

    // ---- the real classes ----------------------------------------------------------------------

    @Test
    @DisplayName("no main class other than the walker calls the leaf method")
    void onlyTheWalkerCallsTheLeafMethod() {
        assertThat(callsTo(CLASSES, SOURCE_TREE, "leaf", c -> c.getFullName().equals(WALKER))).isEmpty();
        assertThat(CLASSES.contain(SOURCE_TREE)).isTrue();
        assertThat(CLASSES.contain(WALKER)).isTrue();
        assertThat(callsTo(CLASSES, SOURCE_TREE, "leaf", c -> false))
                .as("the walker does call it").isNotEmpty();
    }

    @Test
    @DisplayName("no main class calls referenceTree")
    void noMainClassCallsReferenceTree() {
        assertThat(callsTo(CLASSES, SOURCE_TREE, "referenceTree", c -> false)).isEmpty();
    }

    @Test
    @DisplayName("the inspecting reader is read only to look serializers up, and the leaf reader only by the leaf method")
    void readerFieldsAreReadOnlyWhereTheyBelong() {
        assertThat(fieldReads(CLASSES, SOURCE_TREE, "READER", INSPECTING_READERS)).isEmpty();
        assertThat(fieldReads(CLASSES, SOURCE_TREE, "LEAF", Set.of("leaf", "nodes"))).isEmpty();
    }

    @Test
    @DisplayName("inside SourceTree no serialising method is called except in the leaf method and referenceTree")
    void serialisingCallsAreConfined() {
        assertThat(serialisingCalls(CLASSES, Set.of(SOURCE_TREE), Set.of("leaf", "referenceTree"))).isEmpty();
        assertThat(serialisingCalls(CLASSES, Set.of(SOURCE_TREE), Set.of()))
                .as("the leaf method does serialise").isNotEmpty();
    }

    @Test
    @DisplayName("the walker calls no serialising method at all")
    void theWalkerDoesNotSerialise() {
        assertThat(serialisingCalls(CLASSES, Set.of(WALKER), Set.of())).isEmpty();
    }

    // ---- negative fixtures ---------------------------------------------------------------------

    /** Stands in for SourceTree: the rules are parameterised by owner so they can be shown to fire. */
    static final class FakeTree {
        static Object READER;

        static Object leaf(Object value, boolean key) {
            return value;
        }

        static Object referenceTree(Object value) {
            return value;
        }
    }

    static final class CallsLeaf {
        Object call() {
            return FakeTree.leaf("x", false);
        }
    }

    static final class CallsReferenceTree {
        Object call() {
            return FakeTree.referenceTree("x");
        }
    }

    static final class ReadsTheReader {
        Object peek() {
            return FakeTree.READER;
        }
    }

    static final class Serialises {
        void valueToTree(ObjectMapper mapper) {
            mapper.valueToTree("x");
        }

        void convertValue(ObjectMapper mapper) {
            mapper.convertValue("x", String.class);
        }

        void writeValueAsString(ObjectMapper mapper) {
            mapper.writeValueAsString("x");
        }

        void writeValueAsBytes(ObjectMapper mapper) {
            mapper.writeValueAsBytes("x");
        }

        void writerThenWrite(ObjectMapper mapper) {
            mapper.writer().writeValueAsString("x");
        }

        void writerForThenWrite(ObjectMapper mapper) {
            mapper.writerFor(String.class).writeValueAsString("x");
        }

        void serializeValue(SerializationContext context) {
            context.writeValue(null, "x");
        }
    }

    private static JavaClasses fixture(Class<?>... types) {
        return new ClassFileImporter().importClasses(types);
    }

    @Test
    @DisplayName("the leaf-method rule fires on a caller outside the allowed one")
    void theLeafRuleFires() {
        assertThat(callsTo(fixture(CallsLeaf.class), FakeTree.class.getName(), "leaf", c -> false))
                .hasSize(1).first().asString().contains("FakeTree.leaf");
        assertThat(callsTo(fixture(CallsLeaf.class), FakeTree.class.getName(), "leaf",
                c -> c.getName().equals(CallsLeaf.class.getName()))).isEmpty();
    }

    @Test
    @DisplayName("the referenceTree rule fires on any caller")
    void theReferenceTreeRuleFires() {
        assertThat(callsTo(fixture(CallsReferenceTree.class), FakeTree.class.getName(), "referenceTree", c -> false))
                .hasSize(1).first().asString().contains("FakeTree.referenceTree");
    }

    @Test
    @DisplayName("the field rule fires on a read from a method that is not allowed")
    void theFieldRuleFires() {
        assertThat(fieldReads(fixture(ReadsTheReader.class), FakeTree.class.getName(), "READER", Set.of("other")))
                .hasSize(1);
        assertThat(fieldReads(fixture(ReadsTheReader.class), FakeTree.class.getName(), "READER", Set.of("peek")))
                .isEmpty();
    }

    @Test
    @DisplayName("the serialising rule fires on each forbidden call")
    void theSerialisingRuleFires() {
        List<String> found = serialisingCalls(fixture(Serialises.class), Set.of(Serialises.class.getName()), Set.of());
        String all = String.join("\n", found);
        assertThat(all).contains("ObjectMapper.valueToTree").contains("ObjectMapper.convertValue")
                .contains("ObjectMapper.writeValueAsString").contains("ObjectMapper.writeValueAsBytes")
                .contains("ObjectWriter.writeValueAsString").contains("SerializationContext.writeValue");
        assertThat(found).hasSizeGreaterThanOrEqualTo(7);
        assertThat(serialisingCalls(fixture(Serialises.class), Set.of(Serialises.class.getName()),
                Set.of("valueToTree", "convertValue", "writeValueAsString", "writeValueAsBytes", "writerThenWrite",
                        "writerForThenWrite", "serializeValue"))).isEmpty();
    }
}
