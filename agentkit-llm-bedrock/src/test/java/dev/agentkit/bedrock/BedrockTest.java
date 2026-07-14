package dev.agentkit.bedrock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.bedrock.backends.BedrockMantleBackend;
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
        assertThatThrownBy(() -> Bedrock.client((BedrockMantleBackend) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("backend");
    }

    @Test
    void mantleLlmClientRejectsNullRegion() {
        assertThatThrownBy(() -> Bedrock.llmClient((Region) null, ModelResolver.IDENTITY))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("region");
    }

    @Test
    void invokeModelRejectsNullRegion() {
        assertThatThrownBy(() -> Bedrock.invokeModel((Region) null, ModelResolver.IDENTITY))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("region");
    }

    @Test
    void invokeModelRejectsNullResolver() {
        assertThatThrownBy(() -> Bedrock.invokeModel((ModelResolver) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelResolver");
    }
}
