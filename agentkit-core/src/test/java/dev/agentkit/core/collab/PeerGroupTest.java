package dev.agentkit.core.collab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;
import org.junit.jupiter.api.Test;

class PeerGroupTest {

    private static Agent agentReturning(String text) {
        return new Agent(new FakeLlmClient(FakeLlmClient.text(text)),
                new SimpleToolRegistry(), AgentConfig.builder("m").maxSteps(3).build());
    }

    private static Peer peer(String name) {
        return Peer.of(name, name + " does things", () -> agentReturning("done"));
    }

    @Test
    void preservesRegistrationOrderAndLooksUpByName() {
        PeerGroup group = PeerGroup.of(peer("alice"), peer("bob"));

        assertThat(group.names()).containsExactly("alice", "bob");
        assertThat(group.find("bob")).isPresent();
        assertThat(group.find("absent")).isEmpty();
        assertThat(group.isEmpty()).isFalse();
    }

    @Test
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> PeerGroup.of(peer("alice"), peer("alice")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate peer name");
    }

    @Test
    void catalogListsNameAndDescriptionOnePerLine() {
        PeerGroup group = PeerGroup.of(
                Peer.of("researcher", "Finds facts", () -> agentReturning("x")),
                Peer.of("editor", "Reviews drafts", () -> agentReturning("y")));

        assertThat(group.catalog())
                .isEqualTo("- researcher: Finds facts\n- editor: Reviews drafts");
    }

    @Test
    void peerRejectsABlankName() {
        assertThatThrownBy(() -> Peer.of("  ", "desc", () -> agentReturning("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void peerHandleRunsAFreshAgentPerCall() {
        // The single-Agent convenience form reuses the instance; the supplier form
        // builds fresh. Here we verify handle() actually drives the agent.
        Peer p = Peer.of("worker", "works", () -> agentReturning("the result"));
        assertThat(p.handle(Goal.of("go")).output()).isEqualTo("the result");
    }
}
