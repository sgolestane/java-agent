package dev.agentkit.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.FakeLlmClient;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentSkillFlowTest {

    @Test
    void skillFlowsThroughAgentLoop() {
        SkillLibrary library = new SkillLibrary()
                .add(Skill.of("greeter", "greets the user", "Say hello warmly and by name."));

        SimpleToolRegistry registry = Skills.registerInto(new SimpleToolRegistry(), library);
        String systemPrompt = Skills.systemPrompt("You are helpful.", library);

        FakeLlmClient llm = new FakeLlmClient(
                FakeLlmClient.toolUse("r1", "read_skill", Map.of("name", "greeter")),
                FakeLlmClient.text("Hello there!"));

        Agent agent = new Agent(llm, registry,
                AgentConfig.builder("m").systemPrompt(systemPrompt).maxSteps(5).build());
        AgentResult result = agent.run(Goal.of("greet the user"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("Hello there!");

        // tier-1 catalog reached the model via the system prompt.
        assertThat(llm.received().get(0).system()).get()
                .asString().contains("greeter: greets the user");

        // tier-2 instructions were returned by the read_skill tool.
        var toolResultMsg = llm.received().get(1).messages().get(2);
        ToolResultBlock block = (ToolResultBlock) toolResultMsg.content().get(0);
        assertThat(block.content()).contains("Say hello warmly and by name.");
    }

    @Test
    void systemPromptHelperCombinesBaseAndCatalog() {
        SkillLibrary library = new SkillLibrary().add(Skill.of("a", "does A", "i"));
        assertThat(Skills.systemPrompt("base", library)).startsWith("base").contains("a: does A");
        assertThat(Skills.systemPrompt("base", new SkillLibrary())).isEqualTo("base");
    }

    @Test
    void renderInstructionsIncludesResourceHint() {
        Skill skill = new Skill("s", "d", "body",
                java.util.Optional.empty(), java.util.List.of("a.txt", "b.txt"));
        String rendered = skill.renderInstructions("read_skill_resource");
        assertThat(rendered).contains("body").contains("read_skill_resource")
                .contains("a.txt").contains("b.txt");
    }
}
