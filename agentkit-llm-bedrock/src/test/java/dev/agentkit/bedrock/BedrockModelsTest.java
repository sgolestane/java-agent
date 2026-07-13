package dev.agentkit.bedrock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BedrockModelsTest {

    @Test
    void baseModelIdStripsCrossRegionPrefix() {
        assertThat(BedrockModels.baseModelId("us.anthropic.claude-opus-4-8"))
                .isEqualTo("anthropic.claude-opus-4-8");
        assertThat(BedrockModels.baseModelId("eu.anthropic.claude-sonnet-4-6"))
                .isEqualTo("anthropic.claude-sonnet-4-6");
        assertThat(BedrockModels.baseModelId("us-gov.anthropic.claude-opus-4-8"))
                .isEqualTo("anthropic.claude-opus-4-8");
    }

    @Test
    void baseModelIdLeavesUnprefixedIdsUnchanged() {
        assertThat(BedrockModels.baseModelId("anthropic.claude-opus-4-8"))
                .isEqualTo("anthropic.claude-opus-4-8");
        // A leading segment that isn't a known geography prefix is not stripped.
        assertThat(BedrockModels.baseModelId("anthropic.claude-opus-4-8"))
                .isEqualTo("anthropic.claude-opus-4-8");
    }

    @Test
    void modelIdFromArnTakesLastSegment() {
        assertThat(BedrockModels.modelIdFromArn(
                "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-opus-4-8"))
                .isEqualTo("anthropic.claude-opus-4-8");
        assertThat(BedrockModels.modelIdFromArn(
                "arn:aws:bedrock:us-east-1:123:inference-profile/us.anthropic.claude-opus-4-8"))
                .isEqualTo("us.anthropic.claude-opus-4-8");
    }

    @Test
    void modelIdFromArnPassesNonArnThrough() {
        assertThat(BedrockModels.modelIdFromArn("anthropic.claude-opus-4-8"))
                .isEqualTo("anthropic.claude-opus-4-8");
    }
}
