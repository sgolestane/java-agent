package dev.agentkit.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkingMemoryTest {

    @Test
    void notesAreOrderedAndImmutableSnapshot() {
        WorkingMemory wm = new WorkingMemory().note("first").note("second");
        assertThat(wm.notes()).containsExactly("first", "second");
        assertThat(wm.isEmpty()).isFalse();
    }

    @Test
    void blankNotesAreIgnored() {
        WorkingMemory wm = new WorkingMemory().note("   ");
        assertThat(wm.isEmpty()).isTrue();
        assertThat(wm.render()).isEmpty();
    }

    @Test
    void renderBulletsNotes() {
        WorkingMemory wm = new WorkingMemory().note("a").note("b");
        assertThat(wm.render()).isEqualTo("Working notes:\n- a\n- b");
    }

    @Test
    void notesSnapshotIsUnmodifiable() {
        WorkingMemory wm = new WorkingMemory().note("a");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> wm.notes().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void clearEmptiesNotes() {
        WorkingMemory wm = new WorkingMemory().note("x");
        wm.clear();
        assertThat(wm.isEmpty()).isTrue();
    }
}
