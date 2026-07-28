package com.aproject.aidriven.mymobilesecretary.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Prevents future request data from being routed through unreviewed dynamic SQL. */
class DatabaseAccessSafetyArchitectureTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Set<String> REVIEWED_LOW_LEVEL_ACCESS = Set.of(
            "com/aproject/aidriven/mymobilesecretary/account/security/idempotency/IdempotencyService.java",
            "com/aproject/aidriven/mymobilesecretary/account/workspace/DatabaseRoleSafetyVerifier.java",
            "com/aproject/aidriven/mymobilesecretary/account/workspace/WorkspaceRlsJpaDialect.java",
            "com/aproject/aidriven/mymobilesecretary/booking/persistence/BookingExecutionStore.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/adoption/CalendarAdoptionService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/adoption/PersonalRouteProjectionService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/application/CalendarEffectiveOwnerAccess.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/application/CalendarPlanLifecycleService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/application/CalendarSourceMutationSignalService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/attachment/CalendarAttachmentBindingService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/attachment/CalendarAttachmentLifecycleListener.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/knowledge/CalendarKnowledgeBindingReviewListener.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/knowledge/CalendarKnowledgeBindingService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/knowledge/CalendarKnowledgeExcerptService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/knowledge/CalendarKnowledgeIntentTargetResolver.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/knowledge/CalendarKnowledgeReadService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/knowledge/KnowledgeMaterializationExpiryService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/knowledge/KnowledgeMaterializationService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarParticipationAccess.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarParticipationNotificationProcessor.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarPersonalProjectionProcessor.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarParticipationPolicyService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarParticipationRequestStore.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarParticipationService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarOrganizerAssignmentService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarOwnershipTransferService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarRegistrationAccess.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarRegistrationDecisionService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarRegistrationLifecycleService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarRegistrationNotificationProcessor.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarRegistrationPolicyService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarRegistrationService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarRosterService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarSkipService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarWaitlistService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarWaitlistReorderService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/participation/CalendarWatchSubscriptionService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/query/CalendarQueryService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/reminder/CalendarReminderApplicationService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/share/CalendarAuthoritativeCapabilityService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/share/CalendarAuthoritativeMutationService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/share/CalendarEditorMutationService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/share/CalendarSelectedScopeQueryService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/share/CalendarShareContentGrantService.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/share/CalendarShareService.java",
            "com/aproject/aidriven/mymobilesecretary/knowledge/tag/application/CalendarAuthoritativeLifeRecordProcessor.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/task/CalendarNodeFollowUpIntentService.java",
            "com/aproject/aidriven/mymobilesecretary/integration/notification/NotificationPublisher.java",
            "com/aproject/aidriven/mymobilesecretary/knowledge/tag/persistence/TaggedLifeRecordExactlyOnceStore.java",
            "com/aproject/aidriven/mymobilesecretary/reminder/application/TaskReminderRuleService.java");
    private static final Set<String> REVIEWED_NATIVE_QUERIES = Set.of(
            "com/aproject/aidriven/mymobilesecretary/calendar/persistence/CalendarOnlineAccessLinkRepository.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/persistence/CalendarPlanRepository.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/persistence/CalendarTimeNodeRepository.java",
            "com/aproject/aidriven/mymobilesecretary/calendar/task/CalendarTaskBindingRepository.java",
            "com/aproject/aidriven/mymobilesecretary/geo/persistence/GeofenceRuleRepository.java",
            "com/aproject/aidriven/mymobilesecretary/geo/persistence/PlaceRepository.java",
            "com/aproject/aidriven/mymobilesecretary/project/calendar/ProjectCalendarPlanBindingRepository.java",
            "com/aproject/aidriven/mymobilesecretary/project/persistence/ProjectRepository.java",
            "com/aproject/aidriven/mymobilesecretary/travel/persistence/TravelItineraryDraftRepository.java");

    @Test
    void lowLevelDatabaseAccessStaysInsideReviewedParameterBoundFiles() throws IOException {
        Map<String, String> sources = productionSources();

        assertThat(pathsContaining(sources, "JdbcTemplate", "prepareStatement("))
                .as("Use Spring Data JPA by default; low-level SQL requires a security review")
                .containsExactlyInAnyOrderElementsOf(REVIEWED_LOW_LEVEL_ACCESS);
        assertThat(pathsContaining(sources, "nativeQuery = true"))
                .as("Native queries are exceptions and must use named parameters")
                .containsExactlyInAnyOrderElementsOf(REVIEWED_NATIVE_QUERIES);
    }

    @Test
    void unsafeStatementAndJpaEscapeHatchesAreForbidden() throws IOException {
        Map<String, String> sources = productionSources();

        assertThat(pathsContaining(sources, "createStatement(", "JpaSort.unsafe(",
                "createNativeQuery("))
                .as("Do not concatenate request data into executable SQL")
                .isEmpty();
    }

    @Test
    void reviewedNativeQueriesBindEveryRuntimeValue() throws IOException {
        Map<String, String> sources = productionSources();

        for (String path : REVIEWED_NATIVE_QUERIES) {
            String source = sources.get(path);
            assertThat(source).as(path).contains("@Param(");
            assertThat(source).as(path).doesNotContain("#{");
        }
    }

    private static Set<String> pathsContaining(Map<String, String> sources, String... needles) {
        java.util.LinkedHashSet<String> matches = new java.util.LinkedHashSet<>();
        sources.forEach((path, source) -> {
            for (String needle : needles) {
                if (source.contains(needle)) {
                    matches.add(path);
                    break;
                }
            }
        });
        return matches;
    }

    private static Map<String, String> productionSources() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        try (var paths = Files.walk(MAIN_JAVA)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".java"))
                    .toList()) {
                String relative = MAIN_JAVA.relativize(path).toString().replace('\\', '/');
                if (!relative.contains("/internal/")) {
                    sources.put(relative, Files.readString(path));
                }
            }
        }
        return sources;
    }
}
