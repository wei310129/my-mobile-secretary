package com.aproject.aidriven.mymobilesecretary.venue.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.venue.domain.VenueVisitInformation;
import com.aproject.aidriven.mymobilesecretary.venue.domain.VenueVisitInformation.SourceType;
import com.aproject.aidriven.mymobilesecretary.venue.domain.VenueVisitInformation.Status;
import com.aproject.aidriven.mymobilesecretary.venue.persistence.VenueVisitInformationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores venue advisories and surfaces them when a matching visit is scheduled. */
@Service
@Transactional
public class VenueVisitInformationService {

    private static final Pattern VENUE = Pattern.compile(
            "(?:這是|場館(?:是|為)|地點(?:是|為))\\s*(?<venue>[^（(，。；;\\n]{2,80}?)"
                    + "(?:的(?:參觀|展覽|展出|活動)資訊|[，。；;]|$)");
    private final VenueVisitInformationRepository repository;
    private final PlaceService placeService;
    private final UniversalLifeRecordService lifeRecordService;
    private final Clock clock;

    public VenueVisitInformationService(VenueVisitInformationRepository repository,
                                        PlaceService placeService,
                                        UniversalLifeRecordService lifeRecordService,
                                        Clock clock) {
        this.repository = repository;
        this.placeService = placeService;
        this.lifeRecordService = lifeRecordService;
        this.clock = clock;
    }

    public IntentResult ingestImage(String subject, String venueName, String details,
                                    boolean reservationRequired, Integer minimumGroupSize) {
        VenueVisitInformation saved = save(venueName, subject, details,
                reservationRequired, minimumGroupSize, SourceType.IMAGE);
        if (saved.getStatus() == Status.PENDING_VENUE) {
            return IntentResult.clarificationNeeded(
                    "我讀到場館參觀資訊「%s」，但圖片沒有足夠資訊可確認是哪個場館。請告訴我場館名稱；確認前不會建立行程或預約。"
                            .formatted(saved.getSubject()));
        }
        return saved(saved);
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        if (text == null || text.isBlank()) return Optional.empty();
        Optional<VenueVisitInformation> pending = latestPending();
        String venue = extractVenue(text);
        boolean capture = looksLikeCapture(text);
        if (!capture && (pending.isEmpty() || venue == null)) return Optional.empty();
        if (venue == null) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "我可以保存這則參觀資訊，但還不知道要綁定哪個場館。請告訴我場館名稱；我不會從圖片背景猜地點。"));
        }

        beforeMutation.run();
        VenueVisitInformation saved;
        if (pending.isPresent()) {
            saved = pending.get();
            saved.confirmVenue(venue, Instant.now(clock));
        } else {
            saved = save(venue, "參觀／展出資訊", text,
                    text.contains("預約"), mentionedGroupSize(text), SourceType.TEXT);
        }
        recordLifeEvent(saved);
        return Optional.of(saved(saved));
    }

    public IntentResult record(String venueName, String subject, String details,
                               boolean reservationRequired, Integer minimumGroupSize) {
        VenueVisitInformation saved = save(venueName, subject, details,
                reservationRequired, minimumGroupSize, SourceType.TEXT);
        if (saved.getStatus() != Status.ACTIVE) {
            return IntentResult.clarificationNeeded("請告訴我要綁定的場館名稱；我不會自行猜地點。");
        }
        recordLifeEvent(saved);
        return saved(saved);
    }

    @Transactional(readOnly = true)
    public IntentResult query(String keyword) {
        List<VenueVisitInformation> matches = active().stream()
                .filter(info -> keyword == null || keyword.isBlank()
                        || info.matches(keyword)
                        || VenueVisitInformation.normalize(info.getSubject())
                                .contains(VenueVisitInformation.normalize(keyword)))
                .limit(10).toList();
        if (matches.isEmpty()) {
            return IntentResult.message(IntentResult.Action.VENUE_VISIT_INFO,
                    keyword == null || keyword.isBlank()
                            ? "目前沒有已確認的場館參觀資訊。"
                            : "目前沒有「%s」的已確認參觀資訊。".formatted(keyword));
        }
        String body = matches.stream().map(this::summary)
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.VENUE_VISIT_INFO,
                "已保存的場館參觀資訊：\n" + body
                        + "\n以上是你先前保存的內容；預約前請再確認場館最新公告。");
    }

    @Transactional(readOnly = true)
    public IntentResult decorateScheduleResult(IntentResult result) {
        if (result == null || result.decision() == null) return result;
        String candidate = result.decision().item().getTitle();
        Long placeId = result.decision().item().getPlaceId();
        if (placeId != null) {
            candidate += " " + placeService.getPlace(placeId).getName();
        }
        String lookupCandidate = candidate;
        List<VenueVisitInformation> matches = active().stream()
                .filter(info -> info.matches(lookupCandidate)).limit(3).toList();
        if (matches.isEmpty()) return result;
        String advice = matches.stream().map(this::summary)
                .collect(java.util.stream.Collectors.joining("\n"));
        return new IntentResult(result.action(), result.message()
                + "\n\n你先前保存的參觀提醒：\n" + advice
                + "\n預約前請再確認場館最新公告。", result.task(), result.decision());
    }

    private VenueVisitInformation save(String venueName, String subject, String details,
                                       boolean reservationRequired, Integer minimumGroupSize,
                                       SourceType sourceType) {
        String safeSubject = subject == null || subject.isBlank() ? "場館參觀資訊" : subject;
        String safeDetails = details == null || details.isBlank() ? safeSubject : details;
        return repository.save(VenueVisitInformation.create(venueName, safeSubject, safeDetails,
                reservationRequired, minimumGroupSize, sourceType, Instant.now(clock)));
    }

    private Optional<VenueVisitInformation> latestPending() {
        return repository.findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                WorkspaceContextHolder.requireContext().actorId(), Status.PENDING_VENUE);
    }

    private List<VenueVisitInformation> active() {
        return repository.findByCreatedByUserIdAndStatusOrderByUpdatedAtDesc(
                WorkspaceContextHolder.requireContext().actorId(), Status.ACTIVE);
    }

    private void recordLifeEvent(VenueVisitInformation info) {
        List<String> tags = new ArrayList<>(List.of(
                info.getVenueName(), "場館參觀資訊", info.getSubject()));
        if (info.isReservationRequired()) tags.add("需預約");
        lifeRecordService.recordDomainEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                "保存場館參觀資訊：" + info.getVenueName(), Instant.now(clock), tags);
    }

    private IntentResult saved(VenueVisitInformation info) {
        return IntentResult.message(IntentResult.Action.VENUE_VISIT_INFO_SAVED,
                "已保存「%s」的參觀資訊：%s。下次建立或提到這個場館的行程時，我會一併提醒；目前沒有替你建立行程或完成預約。"
                        .formatted(info.getVenueName(), summary(info)));
    }

    private String summary(VenueVisitInformation info) {
        String reservation = info.isReservationRequired() ? "；需預約" : "";
        String group = info.getMinimumGroupSize() == null ? ""
                : "；至少 %d 人".formatted(info.getMinimumGroupSize());
        return "- %s｜%s：%s%s%s".formatted(info.getVenueName(), info.getSubject(),
                info.getDetails(), reservation, group);
    }

    private static boolean looksLikeCapture(String text) {
        String compact = text.replaceAll("\\s+", "");
        boolean venueInfo = compact.contains("參觀資訊") || compact.contains("展出資訊")
                || compact.contains("展覽資訊") || compact.contains("活動可以預約");
        boolean remember = compact.contains("幫我記") || compact.contains("記得")
                || compact.contains("記住") || compact.contains("保存");
        return venueInfo && remember;
    }

    private static String extractVenue(String text) {
        Matcher matcher = VENUE.matcher(text.strip());
        return matcher.find() ? matcher.group("venue").strip() : null;
    }

    private static Integer mentionedGroupSize(String text) {
        Matcher matcher = Pattern.compile("(?<size>\\d{1,3})\\s*人(?:成團|以上|一團)?").matcher(text);
        return matcher.find() ? Integer.valueOf(matcher.group("size")) : null;
    }
}
