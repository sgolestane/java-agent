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
    }

    @Test
    void baseModelIdDoesNotStripSegmentsThatMerelyResembleAGeoPrefix() {
        // Membership is exact — "us-west"/"european" are not in the geo set, so the
        // whole id passes through (guards against a startsWith regression).
        assertThat(BedrockModels.baseModelId("us-west.claude")).isEqualTo("us-west.claude");
        assertThat(BedrockModels.baseModelId("european.claude")).isEqualTo("european.claude");
        assertThat(BedrockModels.baseModelId("usa.claude")).isEqualTo("usa.claude");
    }

    @Test
    void baseModelIdHandlesNoDotAndEmpty() {
        assertThat(BedrockModels.baseModelId("noDotHere")).isEqualTo("noDotHere");
        assertThat(BedrockModels.baseModelId("")).isEqualTo("");
    }

    @Test
    void crossRegionPrefixesTheBaseId() {
        assertThat(BedrockModels.crossRegion("us", BedrockModels.CLAUDE_OPUS_4_8))
                .isEqualTo("us.anthropic.claude-opus-4-8");
        // Replaces an existing geography prefix rather than doubling it.
        assertThat(BedrockModels.crossRegion("eu", "us.anthropic.claude-opus-4-8"))
                .isEqualTo("eu.anthropic.claude-opus-4-8");
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
    void modelIdFromArnHandlesNonArnAndEdges() {
        assertThat(BedrockModels.modelIdFromArn("anthropic.claude-opus-4-8"))
                .isEqualTo("anthropic.claude-opus-4-8");
        assertThat(BedrockModels.modelIdFromArn("arn:...:foundation-model/")).isEqualTo("");
        assertThat(BedrockModels.modelIdFromArn("")).isEqualTo("");
    }
}
