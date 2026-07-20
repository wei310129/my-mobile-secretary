package com.aproject.aidriven.mymobilesecretary.family.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.family.persistence.FamilyPersonAliasRepository;
import com.aproject.aidriven.mymobilesecretary.family.persistence.FamilyPersonAttributeRepository;
import com.aproject.aidriven.mymobilesecretary.family.persistence.FamilyPersonProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FamilyPersonServiceTest {

    @AfterEach
    void clearContext() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void addressQuestionIsNotHijackedByFamilyRecognition() {
        FamilyPersonService service = service(
                mock(FamilyPersonProfileRepository.class),
                mock(FamilyPersonAliasRepository.class),
                mock(FamilyPersonAttributeRepository.class));

        assertThat(service.answer("你知道我女兒上課的夏恩英語地址嗎？", () -> { }))
                .isEmpty();
    }

    @Test
    void colloquialJiaoZuoDoesNotIncludeZuoInThePersonsName() {
        FamilyPersonProfileRepository profiles = mock(FamilyPersonProfileRepository.class);
        FamilyPersonAliasRepository aliases = mock(FamilyPersonAliasRepository.class);
        FamilyPersonAttributeRepository attributes = mock(FamilyPersonAttributeRepository.class);
        var person = mock(com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonProfile.class);
        when(person.getId()).thenReturn(17L);
        when(person.getDisplayLabel()).thenReturn("大女兒");
        when(profiles.findByCreatedByUserIdAndCanonicalKey(actorId(), "eldest_daughter"))
                .thenReturn(Optional.of(person));
        when(attributes.findByCreatedByUserIdAndPersonIdAndKey(
                actorId(), 17L,
                com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonAttribute.Key.NAME))
                .thenReturn(Optional.empty());

        var result = inScope(() -> service(profiles, aliases, attributes)
                .answer("大女兒名字叫做陳禹寧", () -> { }).orElseThrow());

        assertThat(result.message()).contains("姓名：陳禹寧").doesNotContain("姓名：做陳禹寧");
    }

    @Test
    void explicitNameCorrectionIsSupported() {
        FamilyPersonProfileRepository profiles = mock(FamilyPersonProfileRepository.class);
        FamilyPersonAliasRepository aliases = mock(FamilyPersonAliasRepository.class);
        FamilyPersonAttributeRepository attributes = mock(FamilyPersonAttributeRepository.class);
        var person = mock(com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonProfile.class);
        when(person.getId()).thenReturn(18L);
        when(person.getDisplayLabel()).thenReturn("大女兒");
        when(profiles.findByCreatedByUserIdAndCanonicalKey(actorId(), "eldest_daughter"))
                .thenReturn(Optional.of(person));
        when(attributes.findByCreatedByUserIdAndPersonIdAndKey(
                actorId(), 18L,
                com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonAttribute.Key.NAME))
                .thenReturn(Optional.empty());

        var result = inScope(() -> service(profiles, aliases, attributes)
                .answer("把大女兒名字改成「陳禹寧」", () -> { }).orElseThrow());

        assertThat(result.message()).contains("姓名：陳禹寧");
    }

    private static FamilyPersonService service(
            FamilyPersonProfileRepository profiles,
            FamilyPersonAliasRepository aliases,
            FamilyPersonAttributeRepository attributes) {
        return new FamilyPersonService(profiles, aliases, attributes,
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC));
    }

    private static UUID actorId() {
        return UUID.fromString("10000000-0000-0000-0000-000000000001");
    }

    private static <T> T inScope(java.util.concurrent.Callable<T> action) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(actorId(),
                        UUID.fromString("10000000-0000-0000-0000-000000000101"),
                        WorkspaceChannel.LINE))) {
            return action.call();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
