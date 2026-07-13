package dev.agentkit.bedrock;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentkit.anthropic.ModelResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileModel;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileSummary;
import software.amazon.awssdk.services.bedrock.model.InferenceProfileType;

/**
 * Exercises the pure map-building logic against hand-built profile summaries (no
 * AWS calls).
 */
class InferenceProfilesTest {

    private static InferenceProfileSummary appProfile(String name, String arn, String underlyingModelArn) {
        return InferenceProfileSummary.builder()
                .inferenceProfileName(name)
                .inferenceProfileArn(arn)
                .type(InferenceProfileType.APPLICATION)
                .models(List.of(InferenceProfileModel.builder().modelArn(underlyingModelArn).build()))
                .build();
    }

    @Test
    void mapsUnderlyingModelIdAndItsBaseToTheProfileArn() {
        String arn = "arn:aws:bedrock:us-east-1:123:application-inference-profile/opus";
        InferenceProfileSummary profile = appProfile("prod-opus", arn,
                "arn:aws:bedrock:us-east-1:123:inference-profile/us.anthropic.claude-opus-4-8");

        Map<String, String> mapping = InferenceProfiles.mapFromSummaries(List.of(profile), s -> true);

        // Both the exact cross-region id and its base id resolve to the ARN.
        assertThat(mapping).containsEntry("us.anthropic.claude-opus-4-8", arn);
        assertThat(mapping).containsEntry("anthropic.claude-opus-4-8", arn);
    }

    @Test
    void resolverRewritesLogicalIdAndPassesUnknownThrough() {
        String arn = "arn:aws:bedrock:us-east-1:123:application-inference-profile/opus";
        InferenceProfileSummary profile = appProfile("prod-opus", arn,
                "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-opus-4-8");

        ModelResolver resolver = ModelResolver.ofMap(
                InferenceProfiles.mapFromSummaries(List.of(profile), s -> true));

        assertThat(resolver.resolve("anthropic.claude-opus-4-8")).isEqualTo(arn);
        assertThat(resolver.resolve("anthropic.claude-haiku-4-5")).isEqualTo("anthropic.claude-haiku-4-5");
    }

    @Test
    void filterSelectsAmongProfilesForTheSameModel() {
        String modelArn = "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-opus-4-8";
        InferenceProfileSummary dev = appProfile("dev-opus", "arn:dev", modelArn);
        InferenceProfileSummary prod = appProfile("prod-opus", "arn:prod", modelArn);

        Map<String, String> mapping = InferenceProfiles.mapFromSummaries(
                List.of(dev, prod), s -> s.inferenceProfileName().startsWith("prod"));

        assertThat(mapping).containsEntry("anthropic.claude-opus-4-8", "arn:prod");
    }

    @Test
    void firstProfileWinsOnCollisionWithoutFilter() {
        String modelArn = "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-opus-4-8";
        InferenceProfileSummary first = appProfile("a-opus", "arn:first", modelArn);
        InferenceProfileSummary second = appProfile("b-opus", "arn:second", modelArn);

        Map<String, String> mapping = InferenceProfiles.mapFromSummaries(List.of(first, second), s -> true);

        assertThat(mapping).containsEntry("anthropic.claude-opus-4-8", "arn:first");
    }
}
