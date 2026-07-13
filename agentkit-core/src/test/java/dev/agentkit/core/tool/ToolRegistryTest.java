package dev.agentkit.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private static Tool tool(String name) {
        return FunctionTool.builder(name, "desc of " + name)
                .handler(inv -> ToolResult.ok("ok"))
                .build();
    }

    @Test
    void registersAndFindsByName() {
        SimpleToolRegistry registry = new SimpleToolRegistry().register(tool("a")).register(tool("b"));

        assertThat(registry.find("a")).isPresent();
        assertThat(registry.find("missing")).isEmpty();
        assertThat(registry.tools()).hasSize(2);
    }

    @Test
    void advertisesAllSpecsInRegistrationOrder() {
        SimpleToolRegistry registry = new SimpleToolRegistry(java.util.List.of(tool("a"), tool("b")));

        assertThat(registry.advertisedSpecs())
                .extracting(ToolSpec::name)
                .containsExactly("a", "b");
    }

    @Test
    void duplicateRegistrationIsRejected() {
        SimpleToolRegistry registry = new SimpleToolRegistry().register(tool("dup"));
        assertThatThrownBy(() -> registry.register(tool("dup")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void functionToolExposesSpec() {
        Tool t = tool("echo");
        ToolSpec spec = t.spec();
        assertThat(spec.name()).isEqualTo("echo");
        assertThat(spec.description()).isEqualTo("desc of echo");
        assertThat(spec.inputSchema()).containsEntry("type", "object");
    }
}
