package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.conversation.application.RestaurantBookingDraftService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RestaurantBookingDraft;
import com.aproject.aidriven.mymobilesecretary.integration.places.GooglePlacesClient;
import com.aproject.aidriven.mymobilesecretary.integration.places.GooglePlacesClient.RestaurantCandidate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 訂餐廳引導流程(使用者 2026-07-16 裁決 #47):
 * 系統不能真的完成訂位,但**不可只說做不到**——要走替代路徑:
 * 逐輪確認「餐廳/料理、時間、人數與特殊需求」,查營業時間、電話與菜單連結,
 * 給報到與用餐時長建議,結尾祝使用者用餐愉快。
 *
 * 可靠度規則:Google 查詢失敗或未設定金鑰時,引導與建議照常給,流程不可中斷。
 */
@Service
public class RestaurantBookingService {

    private static final Logger log = LoggerFactory.getLogger(RestaurantBookingService.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DINING_TIME =
            DateTimeFormatter.ofPattern("MM/dd(E) HH:mm", Locale.TAIWAN);

    private final GooglePlacesClient placesClient;
    private final RestaurantBookingDraftService drafts;

    public RestaurantBookingService(GooglePlacesClient placesClient) {
        this(placesClient, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RestaurantBookingService(
            GooglePlacesClient placesClient, RestaurantBookingDraftService drafts) {
        this.placesClient = placesClient;
        this.drafts = drafts;
    }

    /**
     * 依已知欄位決定下一步:缺資訊 → 每輪只問下一題;齊了 → 查餐廳並整理訂位須知。
     * 回覆用 RESTAURANT_BOOKING_INFO,不算聽不懂,不進意圖問題清單。
     */
    public IntentResult handle(String text, IntentCommand command) {
        String restaurant = firstNonBlank(command.placeName(), command.title());
        Optional<ZonedDateTime> diningAt = parseDiningTime(text, command);
        Integer partySize = command.safeOptions().quantity();
        String specialNeeds = command.safeOptions().description();

        RestaurantBookingDraft draft = drafts == null ? null : drafts.merge(
                restaurant, diningAt.map(ZonedDateTime::toInstant).orElse(null), partySize,
                hasChildNeed(specialNeeds), hasAccessibilityNeed(specialNeeds),
                hasPetNeed(specialNeeds));
        if (draft != null) {
            restaurant = draft.getRestaurantName();
            diningAt = Optional.ofNullable(draft.getDiningAt())
                    .map(value -> ZonedDateTime.ofInstant(value, TAIPEI));
            partySize = draft.getPartySize();
            specialNeeds = needsSummary(draft);
        }

        if (restaurant == null) {
            return IntentResult.clarificationNeeded(
                    "可以，我先確認餐廳。",
                    ClarificationStep.blocking(
                            "booking.restaurant", "booking.restaurant",
                            "您想吃哪種料理，或是哪間餐廳？", 10));
        }
        if (diningAt.isEmpty()) {
            return IntentResult.clarificationNeeded(
                    ClarificationStep.blocking(
                            "booking.dining-at", "booking.diningAt",
                            "您預計什麼時候用餐？", 20));
        }
        if (partySize == null) {
            return IntentResult.clarificationNeeded(
                    ClarificationStep.blocking(
                            "booking.party-size", "booking.partySize",
                            "總共幾位用餐？", 30));
        }
        IntentResult result = bookingBriefing(
                restaurant, diningAt.get(), partySize, specialNeeds);
        if (draft != null) drafts.complete(draft.getId());
        return result;
    }

    /** 資訊齊了:查餐廳、比對營業時間、對應特殊需求,整理成一則訂位須知。 */
    private IntentResult bookingBriefing(String restaurant, ZonedDateTime diningAt,
                                         int partySize, String specialNeeds) {
        Optional<RestaurantCandidate> found = Optional.empty();
        if (placesClient.usable()) {
            try {
                found = placesClient.searchRestaurantFirst(restaurant);
            } catch (RuntimeException e) {
                // 外部查詢失敗不可讓引導流程死掉:降級成「請來電確認」建議
                log.warn("Restaurant lookup failed for {}", restaurant, e);
            }
        }

        StringBuilder message = new StringBuilder(
                "我先替您整理訂位資訊；目前沒有向餐廳送出訂位或付款。\n");
        if (found.isPresent()) {
            RestaurantCandidate place = found.get();
            message.append("我查到「%s」的公開資訊：".formatted(place.name()));
            if (place.address() != null) {
                message.append("\n地址｜").append(place.address());
            }
            if (place.phoneNumber() != null) {
                message.append("\n電話｜%s（如果要訂位，直接致電最快%s）".formatted(place.phoneNumber(),
                        Boolean.TRUE.equals(place.reservable()) ? ",這間有收訂位" : ""));
            }
            openingLineFor(place, diningAt).ifPresent(line ->
                    message.append("\n當天營業｜%s；您預計 %s 用餐，請確認落在營業時段內".formatted(
                            line, diningAt.format(DINING_TIME))));
            appendNeedsAdvice(message, place, specialNeeds);
            if (place.websiteUri() != null) {
                message.append("\n菜單/官網｜").append(place.websiteUri());
            } else if (place.googleMapsUri() != null) {
                message.append("\n官網沒查到,Google Maps 的照片裡通常找得到菜單:")
                        .append(place.googleMapsUri());
            }
        } else {
            message.append("我目前查不到「%s」的詳細公開資料，先替您整理一般訂位須知：".formatted(restaurant));
            message.append("\n建議直接致電餐廳；您也可以提供完整店名與分店，我可以再查一次。");
        }
        message.append("\n\n訂位小提醒:%d 位、%s 用餐;建議提前 10 分鐘報到,"
                .formatted(partySize, diningAt.format(DINING_TIME)))
                .append("用餐時間一般抓 1.5 小時(人多聚餐可抓 2 小時)。")
                .append("\n如果您要把用餐時段排進行程，或建立「打電話訂位」提醒，可以再告訴我。")
                .append("\n\n祝您用餐愉快!🍽️");
        return IntentResult.message(IntentResult.Action.RESTAURANT_BOOKING_INFO, message.toString());
    }

    /** 找出用餐當天的營業時間描述(Google 回的是「星期五: 11:00 – 21:00」格式)。 */
    private static Optional<String> openingLineFor(RestaurantCandidate place, ZonedDateTime diningAt) {
        String weekday = diningAt.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.TAIWAN);
        return place.openingHours().stream()
                .filter(line -> line.startsWith(weekday))
                .findFirst();
    }

    /** 只回應使用者提到的需求,不要多(長輩/行動不便→無障礙;幼兒→兒童友善;毛小孩→寵物)。 */
    private static void appendNeedsAdvice(StringBuilder message, RestaurantCandidate place,
                                          String specialNeeds) {
        if (specialNeeds == null || specialNeeds.isBlank()) {
            return;
        }
        if (specialNeeds.contains("小孩") || specialNeeds.contains("幼兒")
                || specialNeeds.contains("寶寶") || specialNeeds.contains("兒童")) {
            message.append("\n兒童友善｜").append(threeState(place.goodForChildren(),
                    "這間標示適合帶小朋友", "這間標示不適合兒童,建議換一間或先致電確認",
                    "Google 沒有標示,訂位時記得問一下兒童座椅"));
        }
        if (specialNeeds.contains("長輩") || specialNeeds.contains("老人")
                || specialNeeds.contains("行動不便") || specialNeeds.contains("輪椅")) {
            message.append("\n無障礙｜").append(threeState(place.wheelchairAccessible(),
                    "入口有無障礙標示,長輩進出方便", "入口沒有無障礙標示,建議先致電確認動線",
                    "Google 沒有標示,訂位時記得確認無障礙動線"));
        }
        if (specialNeeds.contains("毛小孩") || specialNeeds.contains("寵物")
                || specialNeeds.contains("狗") || specialNeeds.contains("貓")) {
            message.append("\n寵物｜").append(threeState(place.allowsDogs(),
                    "這間標示寵物友善", "這間標示不能帶寵物,建議換一間",
                    "Google 沒有標示,訂位時記得問能不能帶毛小孩"));
        }
    }

    /** Google 的 Boolean 欄位有三態:true/false/沒提供,話術要分開,不可把「不知道」講成「不行」。 */
    private static String threeState(Boolean value, String yes, String no, String unknown) {
        if (value == null) {
            return unknown;
        }
        return value ? yes : no;
    }

    /** 用餐時間要具體:LLM 沒給、或原話仍是模糊時段(「週末」「晚上」)都算沒定。 */
    private static Optional<ZonedDateTime> parseDiningTime(String text, IntentCommand command) {
        String raw = firstNonBlank(command.startAt(), command.dueAt());
        if (raw == null || VagueTimeGuard.hasVagueTime(text, command.title())) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZonedDateTime.parse(raw).withZoneSameInstant(TAIPEI));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first
                : second != null && !second.isBlank() ? second : null;
    }

    private static boolean hasChildNeed(String value) {
        return containsAny(value, "小孩", "幼兒", "寶寶", "兒童");
    }

    private static boolean hasAccessibilityNeed(String value) {
        return containsAny(value, "長輩", "老人", "行動不便", "輪椅");
    }

    private static boolean hasPetNeed(String value) {
        return containsAny(value, "毛小孩", "寵物", "狗", "貓");
    }

    private static String needsSummary(RestaurantBookingDraft draft) {
        StringBuilder value = new StringBuilder();
        if (draft.isIncludesChild()) value.append("兒童 ");
        if (draft.isRequiresAccessibility()) value.append("輪椅長輩 ");
        if (draft.isIncludesPet()) value.append("毛小孩");
        return value.toString().strip();
    }

    private static boolean containsAny(String value, String... markers) {
        if (value == null) return false;
        for (String marker : markers) {
            if (value.contains(marker)) return true;
        }
        return false;
    }
}
