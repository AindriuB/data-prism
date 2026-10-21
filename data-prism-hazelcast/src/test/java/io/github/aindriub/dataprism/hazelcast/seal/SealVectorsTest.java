package io.github.aindriub.dataprism.hazelcast.seal;

import io.github.aindriub.dataprism.core.crypto.LabelledHkdf;
import io.github.aindriub.dataprism.core.metrics.PrivacyMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static io.github.aindriub.dataprism.hazelcast.seal.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Known-answer vectors for format {@code dps1}. The file pins the output of every derivation for a fixed key,
 * salt and counter, so an accidental change to a label, a prefix width or the envelope grammar fails here.
 * To regenerate after a deliberate change, run with {@code -Dseal.vectors.write=<path to the resource>} and
 * review the diff: a change is a format change and needs a new version.
 */
class SealVectorsTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String RESOURCE = "/seal/dps1-vectors.txt";
    static final List<String> MAPS = List.of(
            "dataprism.identity.s1", "dataprism.reidentification.s1", "dataprism.budget.s1",
            "dataprism.oversight.s1", "dataprism.approval.s1", "dataprism.approval.id.s1",
            "dataprism.approval.pending.s1", "dataprism.callerrate.s1", "dataprism.membercard.s1");

    static List<String> render() {
        ClusterKeys keys = keys();
        KeyedNames names = new KeyedNames(keys);
        Sealer sealer = Sealer.builder(keys, PrivacyMetrics.none()).salt(salt()).initialCounter(1).build();
        List<String> out = new ArrayList<>();
        out.add("format=dps1");
        out.add("cluster.key.id=" + CURRENT_ID);
        out.add("cluster.key=" + HEX.formatHex(currentKey()));
        out.add("name.key.id=" + NAME_ID);
        out.add("name.key=" + HEX.formatHex(nameKey()));
        out.add("salt=" + HEX.formatHex(salt()));
        out.add("counter.first=1");
        out.add("info.example=" + HEX.formatHex(LabelledHkdf.infoText("dataprism/cluster/v1/aead/x", "ctx")));
        for (String map : MAPS) {
            out.add("aead.subkey." + map + "=" + HEX.formatHex(sealer.subkeyForTest(CURRENT_ID, salt(), map)));
        }
        for (String map : MAPS) {
            out.add("name.mapkey." + map + "=" + names.mapKey(map, "subject-0001"));
            out.add("name.lockkey." + map + "=" + names.lockKey(map, "subject-0001"));
            out.add("name.scoped." + map + "=" + names.scoped(map, "scope-0001", "approval-0001"));
        }
        out.add("name.scopetoken=" + names.scopeToken("scope-0001"));
        for (String map : MAPS) {
            out.add("envelope." + map + "=" + sealer.seal(map, names.mapKey(map, "subject-0001"),
                    ("value-for-" + map).getBytes(StandardCharsets.UTF_8)));
        }
        out.add("kcv.cluster=" + HEX.formatHex(KeyCheckValue.compute(CURRENT_ID, currentKey())));
        out.add("kcv.name=" + HEX.formatHex(KeyCheckValue.compute(NAME_ID, nameKey())));
        return out;
    }

    private static List<String> expected() throws IOException {
        try (InputStream in = SealVectorsTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as("vector file %s", RESOURCE).isNotNull();
            List<String> lines = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    lines.add(line);
                }
            }
            return lines;
        }
    }

    @Test
    @DisplayName("regenerating every derivation reproduces the pinned dps1 vector file")
    void vectorsMatch() throws IOException {
        String write = System.getProperty("seal.vectors.write");
        if (write != null) {
            List<String> file = new ArrayList<>();
            file.add("# Known-answer vectors for envelope format dps1 (docs/plan/design/cluster-security.md 2.3.1, 4.7).");
            file.add("# Fixed invented test bytes only. A change here is a format change.");
            file.addAll(render());
            Files.writeString(Path.of(write), String.join("\n", file) + "\n");
        }
        assertThat(render()).isEqualTo(expected());
    }

    @Test
    @DisplayName("the vector file names the format version dps1 in its header and its first entry")
    void headerNamesTheFormat() throws IOException {
        try (InputStream in = SealVectorsTest.class.getResourceAsStream(RESOURCE)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(text.lines().findFirst().orElseThrow()).startsWith("#").contains("dps1");
        }
        assertThat(expected().get(0)).isEqualTo("format=dps1");
    }

    @Test
    @DisplayName("each pinned envelope opens to its plaintext")
    void pinnedEnvelopesOpen() throws IOException {
        Sealer reader = Sealer.builder(keys(), PrivacyMetrics.none()).build();
        KeyedNames names = new KeyedNames(keys());
        for (String line : expected()) {
            if (line.startsWith("envelope.")) {
                String map = line.substring("envelope.".length(), line.indexOf('='));
                Opened opened = reader.open(map, names.mapKey(map, "subject-0001"), line.substring(line.indexOf('=') + 1));
                assertThat(opened.outcome()).isEqualTo(Opened.Outcome.AUTHENTIC);
                assertThat(new String(opened.plaintext(), StandardCharsets.UTF_8)).isEqualTo("value-for-" + map);
            }
        }
    }

    @Test
    @DisplayName("the key check value is eight bytes and differs by key id and by material")
    void kcvProperties() {
        byte[] a = KeyCheckValue.compute("k1", currentKey());
        assertThat(a).hasSize(8);
        assertThat(KeyCheckValue.compute("k2", currentKey())).isNotEqualTo(a);
        assertThat(KeyCheckValue.compute("k1", nextKey())).isNotEqualTo(a);
        assertThat(KeyCheckValue.compute("k1", currentKey())).isEqualTo(a);
    }
}
