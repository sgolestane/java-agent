package dev.agentkit.bedrock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.anthropic.ModelResolver;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;

/**
 * Boundary null-checks on the {@link Bedrock} factory — the paths that don't need
 * AWS credentials. The credential/region-dependent factories are exercised by the
 * runnable examples, not unit tests.
 */
class BedrockTest {

    @Test
    void clientRejectsNullBackend() {
        assertThatThrownBy(() -> Bedrock.client(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("backend");
    }

    @Test
    void llmClientRejectsNullRegion() {
        assertThatThrownBy(() -> Bedrock.llmClient((Region) null, ModelResolver.IDENTITY))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("region");
    }
}
