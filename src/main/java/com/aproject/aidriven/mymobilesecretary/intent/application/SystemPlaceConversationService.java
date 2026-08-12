package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftConversationService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.PublicPlaceLookupDraftService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PublicPlaceLookupDraftMode;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.SystemPlaceCatalog;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.domain.SystemPlaceCategory;
import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Deterministic secretary replies for product-owned public-place knowledge. */
@Service
public class SystemPlaceConversationService {

    private static final String NUMBER_TOKEN =
            "(?:\\d{1,2}|[〇零一二兩三四五六七八九十廿]{1,3})";
    private static final Pattern DATE_EXPRESSION = Pattern.compile(
            ".*(?:今天|明天|後天|大後天|(?:本|下)?(?:週|星期)[一二三四五六日天]|"
                    + NUMBER_TOKEN + "月" + NUMBER_TOKEN + "日?|"
                    + NUMBER_TOKEN + "/" + NUMBER_TOKEN + ").*");
    private static final Pattern TIME_EXPRESSION = Pattern.compile(
            ".*(?:凌晨|早上|上午|中午|下午|傍晚|晚上)?"
                    + NUMBER_TOKEN + "(?:點|時).*");

    private final SystemPlaceCatalog catalog;
    private final PlaceAliasService aliases;
    private PublicPlaceLookupDraftService lookupDrafts;
    private CalendarIntentDraftConversationService calendarDrafts;

    public SystemPlaceConversationService(
            SystemPlaceCatalog catalog, PlaceAliasService aliases) {
        this.catalog = catalog;
        this.aliases = aliases;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLookupDrafts(PublicPlaceLookupDraftService lookupDrafts) {
        this.lookupDrafts = lookupDrafts;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCalendarDrafts(CalendarIntentDraftConversationService calendarDrafts) {
        this.calendarDrafts = calendarDrafts;
    }

    public Optional<IntentResult> answer(String text) {
        String compact = compact(text);
        if (compact.isBlank() || isMutation(compact) || isPlanningTurn(compact)
                || isRouteOrTemporalPropertyTurn(compact)) {
            return Optional.empty();
        }
        SystemPlaceCatalog.Resolution resolution = catalog.resolveMention(text);
        PlaceReadOperation operation = operation(compact);
        if (lookupDrafts != null) {
            Optional<com.aproject.aidriven.mymobilesecretary.conversation.domain.PublicPlaceLookupDraft>
                    currentLookup = lookupDrafts.current();
            boolean calendarLocation = currentLookup
                    .map(draft -> draft.getMode() == PublicPlaceLookupDraftMode.CALENDAR_LOCATION)
                    .orElse(false);
            if (calendarLocation && calendarDrafts != null) {
                Optional<IntentResult> calendar = calendarDrafts.answerSystemPlaceRegion(text);
                if (calendar.isPresent()) return calendar;
            }
            boolean multipointBrowse = currentLookup
                    .map(draft -> draft.getMode()
                            == PublicPlaceLookupDraftMode.READ_ONLY_MULTIPOINT)
                    .orElse(false);
            boolean continuation = resolution.status()
                    == SystemPlaceCatalog.Resolution.Status.NOT_FOUND
                    || resolution.status()
                            == SystemPlaceCatalog.Resolution.Status.CATEGORY_ONLY;
            if (multipointBrowse && continuation && operation == PlaceReadOperation.LIST_POINTS) {
                return lookupDrafts.browse(null).map(answer -> pointList(answer.matches(), false));
            }
            if (multipointBrowse && continuation && operation == PlaceReadOperation.FILTER_POINTS) {
                return lookupDrafts.browse(text).map(answer -> answer.matches().isEmpty()
                        ? noPointKeywordMatch()
                        : pointList(answer.matches(), true));
            }
            if (!multipointBrowse) {
                Optional<PublicPlaceLookupDraftService.Answer> continued =
                        lookupDrafts.answerRegion(text);
                if (continued.isPresent()) {
                    var answer = continued.orElseThrow();
                    return Optional.of(IntentResult.message(
                            IntentResult.Action.PLACE_INFO,
                            "依你補充的縣市，系統公共地點是「%s」｜%s。"
                                    .formatted(answer.selected().name(), answer.selected().location())
                                    + "這次只是查詢，沒有建立或修改自訂地點。"));
                }
            }
        }
        boolean capabilityQuestion = containsAny(
                compact, "系統地點", "系統自己的地點", "自己的地點", "公共地點資料", "內建地點");
        capabilityQuestion = capabilityQuestion
                || (compact.contains("系統") && compact.contains("自己")
                        && compact.contains("地點"))
                || categoryMentionCount(compact) >= 3;
        boolean readQuestion = containsAny(
                compact, "在哪", "什麼地方", "地址", "位置", "地點", "有沒有", "有哪些",
                "知道", "認得", "熟悉", "聽過", "資料");
        readQuestion = readQuestion
                || operation == PlaceReadOperation.LIST_POINTS
                || operation == PlaceReadOperation.FILTER_POINTS;
        boolean shortCategory = resolution.status()
                        == SystemPlaceCatalog.Resolution.Status.CATEGORY_ONLY
                && compact.length() <= 12;
        if (operation == PlaceReadOperation.LIST_POINTS
                && resolution.status() == SystemPlaceCatalog.Resolution.Status.NOT_FOUND) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "我可以幫您查系統點位。",
                    ClarificationStep.blocking(
                            "place.point-location", "place.name",
                            "您想查哪個地點的系統點位？", 10)));
        }
        if (!capabilityQuestion && !readQuestion && !shortCategory) return Optional.empty();

        if (!capabilityQuestion && readQuestion) {
            Optional<Place> custom = aliases.resolveMention(text);
            if (custom.isPresent()) {
                Place place = custom.orElseThrow();
                if (place.getAddress() == null || place.getAddress().isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(IntentResult.message(
                        IntentResult.Action.PLACE_INFO,
                        "你的自訂地點「%s」｜%s。這次只是查詢，沒有建立或修改資料。"
                                .formatted(place.getName(), place.getAddress())));
            }
        }

        if (capabilityQuestion) {
            return Optional.of(IntentResult.message(
                    IntentResult.Action.PLACE_INFO, capabilityReply(compact)));
        }
        return switch (resolution.status()) {
            case EXACT -> Optional.of(exact(resolution.selected()));
            case LOGICAL_PLACE_MULTIPOINT -> Optional.of(multiPoint(text, operation, resolution));
            case ENTITY_AMBIGUOUS -> Optional.of(ambiguous(resolution));
            case CATEGORY_ONLY -> Optional.of(category(resolution.category()));
            case NOT_FOUND -> Optional.empty();
        };
    }

    private String capabilityReply(String compact) {
        String correction = containsAny(compact, "不是", "我是說", "之前", "講過", "應該")
                ? "你說的是系統自有公共地點，不是要建立使用者自訂地點。"
                : "系統有自己的公共地點資料。";
        return correction + "目前內建：" + SystemPlaceCategory.stream()
                .map(category -> "%s %d 筆".formatted(
                        category.publicLabel(), catalog.categoryCounts().get(category)))
                .collect(java.util.stream.Collectors.joining("、"))
                + "。查詢不會新增或修改你的自訂地點；用於行程時，唯一結果會直接引用，"
                + "同一大型場站有多個點位則在整體安排定案時選定並說明原因。";
    }

    private IntentResult exact(SystemPlaceCatalog.SystemPlace place) {
        if (aliases.resolve(place.name()).isPresent()) return nullSafeEmpty();
        return IntentResult.plainMessage(
                IntentResult.Action.PLACE_INFO, knownPlaceReply(place));
    }

    private static String knownPlaceReply(SystemPlaceCatalog.SystemPlace place) {
        String region = SystemPlaceCatalog.regionLabel(place);
        if (place.category() == SystemPlaceCategory.METRO_STATION) {
            return "我知道，您說的是「%s」位於%s的「%s」。"
                    .formatted(metroSystemLabel(place.key()), region, metroStationName(place.name()));
        }
        return region.isBlank()
                ? "我知道，您說的是「%s」。".formatted(place.name())
                : "我知道，您說的是位於%s的「%s」。".formatted(region, place.name());
    }

    private static String metroSystemLabel(String key) {
        if (key.contains(":TRTC-")) return "台北捷運";
        if (key.contains(":NTMCC-")) return "新北捷運";
        if (key.contains(":TYMC-")) return "桃園捷運";
        if (key.contains(":TMRT-")) return "台中捷運";
        if (key.contains(":KRTC-")) return "高雄捷運";
        return "捷運";
    }

    private static String metroStationName(String name) {
        String station = name.endsWith("站") ? name : name + "站";
        return station.startsWith("捷運") ? station : "捷運" + station;
    }

    private IntentResult multiPoint(
            String text, PlaceReadOperation operation,
            SystemPlaceCatalog.Resolution resolution) {
        if (lookupDrafts != null) lookupDrafts.startMultipoint(resolution);
        if (operation == PlaceReadOperation.FILTER_POINTS) {
            var matches = catalog.filterCandidates(
                    resolution.candidates().stream().map(SystemPlaceCatalog.SystemPlace::key).toList(),
                    text);
            return matches.isEmpty() ? noPointKeywordMatch() : pointList(matches, true);
        }
        if (operation == PlaceReadOperation.LIST_POINTS) {
            return pointList(resolution.candidates(), false);
        }
        SystemPlaceCatalog.LogicalPlace logical = resolution.logicalPlace();
        String name = logical == null ? resolution.selected().name() : logical.name();
        String region = logical == null
                ? SystemPlaceCatalog.regionLabel(resolution.selected()) : logical.region();
        String reply = "我知道，您說的是%s「%s」。目前有 %d 個系統點位，"
                .formatted(region.isBlank() ? "" : "位於" + region + "的", name,
                        resolution.candidates().size())
                + "有需要我可以列出來給您，或者您也可以提供關鍵字讓我幫您查詢。";
        return IntentResult.message(IntentResult.Action.PLACE_INFO, reply);
    }

    private static IntentResult pointList(
            java.util.List<SystemPlaceCatalog.SystemPlace> points, boolean filtered) {
        String heading = filtered
                ? "找到 %d 個符合的系統點位：".formatted(points.size())
                : "目前有 %d 個系統點位：".formatted(points.size());
        String rows = java.util.stream.IntStream.range(0, points.size())
                .mapToObj(index -> "%d. %s".formatted(index + 1, pointLabel(points.get(index))))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.PLACE_INFO, heading + "\n" + rows);
    }

    private static String pointLabel(SystemPlaceCatalog.SystemPlace point) {
        if (point.category() == SystemPlaceCategory.METRO_STATION) {
            return "%s「%s」".formatted(
                    metroSystemLabel(point.key()), metroStationName(point.name()));
        }
        return "%s「%s」".formatted(point.category().publicLabel(), point.name());
    }

    private static IntentResult noPointKeywordMatch() {
        return IntentResult.message(
                IntentResult.Action.PLACE_INFO,
                "目前這些系統點位沒有符合的結果。您可以換一個地點、路線或營運系統關鍵字。");
    }

    private IntentResult ambiguous(SystemPlaceCatalog.Resolution resolution) {
        if (lookupDrafts != null) {
            lookupDrafts.start(resolution, PublicPlaceLookupDraftMode.READ_ONLY);
        }
        String label = resolution.category() == null
                ? "這個名稱" : resolution.category().publicLabel();
        return IntentResult.clarificationNeeded(
                "系統找到分屬不同縣市的同名地點；目前不會自行選擇，也沒有建立或修改資料。",
                ClarificationStep.blocking(
                        "place.system-region", "place.region",
                        "您指的是哪個縣市的%s？".formatted(label), 10));
    }

    private IntentResult category(SystemPlaceCategory category) {
        return IntentResult.message(
                IntentResult.Action.PLACE_INFO,
                "系統目前有 %d 筆%s資料。你現在不用先選縣市或實際點位；"
                        .formatted(catalog.categoryCounts().get(category), category.publicLabel())
                        + "等需求進入行程規劃、而且結果不能唯一判定時，我才會問一個必要問題。"
                        + "這次沒有建立或修改自訂地點。");
    }

    private static IntentResult nullSafeEmpty() {
        return IntentResult.message(
                IntentResult.Action.PLACE_INFO,
                "這個名稱已對應到你的自訂地點；系統公共地點不會覆蓋它。");
    }

    private static boolean isMutation(String text) {
        return containsAny(text,
                "新增", "建立", "加入", "安排", "排進", "幫我排", "修改", "改成",
                "取消", "刪除", "綁定", "提醒我");
    }

    private static boolean isPlanningTurn(String text) {
        if (isExplicitQuestion(text)) return false;
        boolean timed = DATE_EXPRESSION.matcher(text).matches()
                && TIME_EXPRESSION.matcher(text).matches();
        boolean structured = DATE_EXPRESSION.matcher(text).matches()
                && text.contains("日期")
                && containsAny(text, "地點", "地址", "場地");
        return timed || structured;
    }

    private static boolean isRouteOrTemporalPropertyTurn(String text) {
        boolean routeReference = containsAny(text,
                "從", "到", "前往", "出發", "抵達", "搭車", "轉乘", "交通", "路線");
        boolean serviceProperty = containsAny(text,
                "幾點有車", "最晚幾點", "末班", "首班", "班次", "營業時間", "開放時間");
        return routeReference || serviceProperty;
    }

    private static boolean isExplicitQuestion(String text) {
        return containsAny(text, "哪裡", "在哪", "什麼地址", "哪個位置", "幾點");
    }

    private static boolean isPointListRequest(String text) {
        boolean pointReference = text.contains("點位") || text.contains("個點");
        return pointReference
                && containsAny(text, "哪些", "有什麼", "列出", "列給我", "顯示", "給我看", "看看");
    }

    private static PlaceReadOperation operation(String text) {
        if (isPointListRequest(text) && isPointKeywordFollowUp(text)) {
            return PlaceReadOperation.FILTER_POINTS;
        }
        if (isPointListRequest(text)) return PlaceReadOperation.LIST_POINTS;
        if (isPointKeywordFollowUp(text)) return PlaceReadOperation.FILTER_POINTS;
        if (containsAny(text, "在哪", "地址", "位置")) return PlaceReadOperation.LOCATION;
        return PlaceReadOperation.KNOWLEDGE;
    }

    private static boolean isPointKeywordFollowUp(String text) {
        if (containsAny(text, "關鍵字", "幫我找", "幫我查", "搜尋")) return true;
        return text.length() <= 16 && containsAny(
                text, "台北捷運", "新北捷運", "桃園捷運", "機場捷運", "台鐵", "火車", "高鐵", "捷運");
    }

    private static String compact(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\s，。！？!?、；;：:]", "");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    private static long categoryMentionCount(String text) {
        return java.util.stream.Stream.of(
                        "捷運", "港口", "機場", "高鐵", "火車", "政府", "遊樂園")
                .filter(text::contains)
                .count();
    }

    private enum PlaceReadOperation {
        KNOWLEDGE,
        LOCATION,
        LIST_POINTS,
        FILTER_POINTS
    }
}
