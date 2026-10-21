package io.github.aindriub.dataprism.hazelcast.spike;

import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Question 2: what does a 5.7 log line carry, and in what format? */
class Q02LogLineSpikeTest {

    private HazelcastInstance member;

    @AfterEach
    void stop() {
        Spike.shutdown(member);
    }

    @Test
    void aLogLineCarriesTheAddressThenTheClusterNameThenTheVersionEachInBrackets() {
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        String clusterName = Spike.name("q2");
        try {
            member = Spike.start(Spike.config(clusterName, SpikePorts.consecutive(1), List.of()));
            member.getMap("m").put("k", "v");
        } finally {
            root.removeHandler(handler);
        }
        // "[address]:port [cluster name] [version] message", the prefix being part of the message text.
        Pattern format = Pattern.compile("\\[127\\.0\\.0\\.1\\]:\\d+ \\[" + Pattern.quote(clusterName)
                + "\\] \\[5\\.7\\.0\\] .*", Pattern.DOTALL);
        List<String> started = records.stream()
                .filter(r -> r.getLoggerName() != null && r.getLoggerName().startsWith("com.hazelcast.system"))
                .map(LogRecord::getMessage).toList();
        assertThat(started).isNotEmpty().allMatch(m -> format.matcher(m).matches());
        assertThat(started).anyMatch(m -> m.endsWith("Cluster name: " + clusterName));
    }
}
