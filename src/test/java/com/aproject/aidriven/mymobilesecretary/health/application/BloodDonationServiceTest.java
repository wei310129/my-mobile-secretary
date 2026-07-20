package com.aproject.aidriven.mymobilesecretary.health.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.health.domain.BloodDonationRecord;
import com.aproject.aidriven.mymobilesecretary.health.persistence.BloodDonationRecordRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BloodDonationServiceTest {
    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORKSPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-07-18T08:00:00Z");

    @Test
    void imageRecordKeepsEligibilityUnknownAndUsesActorScopedLookup() {
        BloodDonationRecordRepository repository = mock(BloodDonationRecordRepository.class);
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);
        UniversalLifeRecordService life = mock(UniversalLifeRecordService.class);
        LocalDate donated = LocalDate.of(2026, 7, 1);
        when(repository.findByCreatedByUserIdAndDonationDate(ACTOR, donated))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        BloodDonationService service = new BloodDonationService(repository, graph, life,
                Clock.fixed(NOW, ZoneOffset.UTC));

        BloodDonationRecord result;
        try (var ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.LINE))) {
            result = service.record(donated, "公園捐血車", null,
                    BloodDonationRecord.SourceType.IMAGE);
        }

        assertThat(result.getNextEligibleDate()).isNull();
        verify(repository).findByCreatedByUserIdAndDonationDate(ACTOR, donated);
        verify(graph).indexBloodDonation(nullable(Long.class), any());
        verify(life).recordDomainEvent(any(), any(), any(), any());
    }
}
