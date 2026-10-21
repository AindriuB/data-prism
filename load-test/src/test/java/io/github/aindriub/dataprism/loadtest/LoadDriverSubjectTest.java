package io.github.aindriub.dataprism.loadtest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoadDriverSubjectTest {

    @Test
    void withoutAPoolEveryCallAsksForADifferentSubject() {
        assertThat(LoadDriver.subjectFor("m", 0, 0)).isEqualTo("m-00000000");
        assertThat(LoadDriver.subjectFor("m", 7, 0)).isEqualTo("m-00000007");
    }

    @Test
    void withAPoolTheSubjectsCycleSoTheSameSubjectIsReadAgain() {
        assertThat(LoadDriver.subjectFor("m", 0, 3)).isEqualTo("m-00000000");
        assertThat(LoadDriver.subjectFor("m", 3, 3)).isEqualTo("m-00000000");
        assertThat(LoadDriver.subjectFor("m", 5, 3)).isEqualTo("m-00000002");
    }
}
