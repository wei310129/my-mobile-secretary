package com.aproject.aidriven.mymobilesecretary.contact.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.contact.domain.ExternalContact;
import com.aproject.aidriven.mymobilesecretary.contact.persistence.ExternalContactRepository;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptCommand.ContactCard;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExternalContactServiceTest {

    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORKSPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void importsReadableCardAndIndexesItWithoutLlmExecution() {
        ExternalContactRepository repository = mock(ExternalContactRepository.class);
        SemanticTagGraphService graph = mock(SemanticTagGraphService.class);
        UniversalLifeRecordService life = mock(UniversalLifeRecordService.class);
        when(repository.findByCreatedByUserIdAndCanonicalKey(ACTOR, "0912345678"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ExternalContactService service = new ExternalContactService(repository, graph, life,
                Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC));
        ContactCard card = new ContactCard("王師傅", "安心水電", "水電師傅",
                List.of("0912-345-678"), List.of("service@example.com"), "台北市信義區");

        ExternalContactService.ImportResult result;
        try (var ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.LINE))) {
            result = service.importBusinessCard(card);
        }

        assertThat(result.created()).isTrue();
        assertThat(result.contact().getDisplayName()).isEqualTo("王師傅");
        assertThat(result.contact().getPhoneNumber()).isEqualTo("0912-345-678");
        verify(graph).indexExternalContact(any(), any());
        verify(life).recordDomainEvent(any(), any(), any(), any());
    }

    @Test
    void searchIsExplicitlyActorScoped() {
        ExternalContactRepository repository = mock(ExternalContactRepository.class);
        ExternalContact plumber = mock(ExternalContact.class);
        when(plumber.getDisplayName()).thenReturn("王師傅");
        when(plumber.getProfession()).thenReturn("水電師傅");
        when(repository.findByCreatedByUserIdOrderByUpdatedAtDesc(ACTOR))
                .thenReturn(List.of(plumber));
        ExternalContactService service = new ExternalContactService(repository,
                mock(SemanticTagGraphService.class), mock(UniversalLifeRecordService.class),
                Clock.systemUTC());

        List<ExternalContact> result;
        try (var ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.REST))) {
            result = service.search("水電");
        }

        assertThat(result).containsExactly(plumber);
        verify(repository).findByCreatedByUserIdOrderByUpdatedAtDesc(ACTOR);
    }
}
