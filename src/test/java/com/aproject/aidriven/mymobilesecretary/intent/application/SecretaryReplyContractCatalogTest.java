package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SecretaryReplyContractCatalogTest {

    private static final Path INVENTORY = Path.of(
            "docs/exec-plans/active/conversation-public-reply-tone-inventory.md");
    private static final Pattern TEMPLATE_ID = Pattern.compile("(?m)^\\| ([A-Z][A-Z0-9-]*) \\|");

    @Test
    void everyCurrentAndFuturePublicActionMustResolveToAReviewedInventoryTemplate()
            throws Exception {
        Set<String> reviewedIds = reviewedTemplateIds();

        assertThat(Arrays.stream(IntentResult.Action.values())
                .map(SecretaryReplyContractCatalog::forAction)
                .map(SecretaryReplyContractCatalog.Contract::templateId))
                .allMatch(reviewedIds::contains);
    }

    @Test
    void catalogIncludesEveryRequiredClaimClass() {
        assertThat(Arrays.stream(IntentResult.Action.values())
                .map(SecretaryReplyContractCatalog::forAction)
                .map(SecretaryReplyContractCatalog.Contract::claimClass)
                .distinct())
                .containsExactlyInAnyOrder(
                        SecretaryReplyContractCatalog.ClaimClass.values());
    }

    @Test
    void everyActionContractCarriesItsRuntimeDefaultEvidence() {
        assertThat(Arrays.stream(IntentResult.Action.values())
                .map(SecretaryReplyContractCatalog::forAction)
                .allMatch(contract -> !contract.defaultEvidence().isEmpty()))
                .isTrue();

        assertThat(SecretaryReplyContractCatalog.forAction(
                        IntentResult.Action.KNOWLEDGE_SAVED).defaultEvidence())
                .containsExactly(PublicReplyEvidence.MUTATION_COMMITTED);
        assertThat(SecretaryReplyContractCatalog.forAction(
                        IntentResult.Action.TASKS_LISTED).defaultEvidence())
                .containsExactly(PublicReplyEvidence.QUERY_COMPLETED);
        assertThat(SecretaryReplyContractCatalog.forAction(
                        IntentResult.Action.FEEDBACK_RECEIVED).defaultEvidence())
                .containsExactly(PublicReplyEvidence.ACKNOWLEDGED_ONLY);
        assertThat(SecretaryReplyContractCatalog.forAction(
                        IntentResult.Action.PLACE_CATALOG_INFO).defaultEvidence())
                .containsExactly(PublicReplyEvidence.ENTITY_RESOLVED);
        assertThat(SecretaryReplyContractCatalog.forAction(
                        IntentResult.Action.PLACE_CATALOG_ADOPTED).claimClass())
                .isEqualTo(SecretaryReplyContractCatalog.ClaimClass.QUERY);
        assertThat(SecretaryReplyContractCatalog.forAction(
                        IntentResult.Action.PLACE_CATALOG_ADOPTED).defaultEvidence())
                .containsExactly(PublicReplyEvidence.ENTITY_RESOLVED);
    }

    private static Set<String> reviewedTemplateIds() throws Exception {
        String content = Files.readString(INVENTORY, StandardCharsets.UTF_8);
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        Matcher matcher = TEMPLATE_ID.matcher(content);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return Set.copyOf(ids);
    }
}
