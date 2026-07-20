package com.aproject.aidriven.mymobilesecretary.contact.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.contact.domain.ExternalContact;
import com.aproject.aidriven.mymobilesecretary.contact.persistence.ExternalContactRepository;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptCommand.ContactCard;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTag;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic import and actor-scoped lookup for external professional contacts. */
@Service
@Transactional
public class ExternalContactService {

    private static final int MAX_RESULTS = 10;

    private final ExternalContactRepository repository;
    private final SemanticTagGraphService tagGraphService;
    private final UniversalLifeRecordService lifeRecordService;
    private final Clock clock;

    public ExternalContactService(ExternalContactRepository repository,
                                  SemanticTagGraphService tagGraphService,
                                  UniversalLifeRecordService lifeRecordService,
                                  Clock clock) {
        this.repository = repository;
        this.tagGraphService = tagGraphService;
        this.lifeRecordService = lifeRecordService;
        this.clock = clock;
    }

    public ImportResult importBusinessCard(ContactCard card) {
        if (card == null) throw new IllegalArgumentException("business card fields are required");
        String displayName = firstPresent(card.displayName(), card.organizationName(), card.profession());
        if (displayName == null) throw new IllegalArgumentException("business card identity is unreadable");
        String phone = first(card.phoneNumbers());
        String email = first(card.emails());
        String canonicalKey = canonicalKey(displayName, card.organizationName(), phone, email);
        var actorId = WorkspaceContextHolder.requireContext().actorId();
        Instant now = Instant.now(clock);
        ExternalContact existing = repository
                .findByCreatedByUserIdAndCanonicalKey(actorId, canonicalKey).orElse(null);
        if (existing != null) {
            existing.mergeMissing(card.organizationName(), card.profession(), phone,
                    email, card.address(), now);
            return new ImportResult(repository.save(existing), false);
        }
        ExternalContact saved = repository.saveAndFlush(ExternalContact.create(
                canonicalKey, displayName, card.organizationName(), card.profession(),
                phone, email, card.address(), now));
        index(saved);
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                "新增名片聯絡人：" + saved.getDisplayName(), now,
                List.of("名片", "聯絡人", nullSafe(saved.getProfession(), "專業服務")));
        return new ImportResult(saved, true);
    }

    @Transactional(readOnly = true)
    public List<ExternalContact> search(String keyword) {
        String needle = normalize(keyword);
        return repository.findByCreatedByUserIdOrderByUpdatedAtDesc(
                        WorkspaceContextHolder.requireContext().actorId()).stream()
                .filter(contact -> needle == null
                        || contains(contact.getDisplayName(), needle)
                        || contains(contact.getOrganizationName(), needle)
                        || contains(contact.getProfession(), needle))
                .limit(MAX_RESULTS).toList();
    }

    private void index(ExternalContact contact) {
        List<SemanticTagGraphService.TagSpec> tags = new ArrayList<>();
        tags.add(tag("聯絡人", SemanticTag.Kind.CATEGORY));
        tags.add(tag("名片", SemanticTag.Kind.TOPIC));
        tags.add(tag(contact.getDisplayName(), SemanticTag.Kind.TOPIC));
        if (contact.getOrganizationName() != null) {
            tags.add(tag(contact.getOrganizationName(), SemanticTag.Kind.ORGANIZATION));
        }
        if (contact.getProfession() != null) {
            tags.add(tag(contact.getProfession(), SemanticTag.Kind.SERVICE));
        }
        tagGraphService.indexExternalContact(contact.getId(), tags);
    }

    private static SemanticTagGraphService.TagSpec tag(String name, SemanticTag.Kind kind) {
        return new SemanticTagGraphService.TagSpec(name, kind, SemanticTagEdge.SourceType.IMPORT);
    }

    private static String canonicalKey(String name, String organization, String phone, String email) {
        String strong = firstPresent(normalize(phone), normalize(email));
        return strong == null ? normalize(name) + "|" + nullSafe(normalize(organization), "") : strong;
    }

    private static String first(List<String> values) {
        if (values == null) return null;
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::strip).findFirst().orElse(null);
    }

    private static String firstPresent(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.strip();
        return null;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null
                : value.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s()－—-]", "");
    }

    private static boolean contains(String value, String needle) {
        return value != null && normalize(value).contains(needle);
    }

    private static String nullSafe(String value, String fallback) {
        return value == null ? fallback : value;
    }

    public record ImportResult(ExternalContact contact, boolean created) {
    }
}
