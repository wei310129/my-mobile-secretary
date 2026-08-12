package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarRouteRiskResponsePolicy;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationChoiceQuestion;
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationChoiceRenderer;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

@Component
public class CalendarIntentDraftPreflightResponsePolicy {

    private static final ZoneId PUBLIC_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final CalendarRouteRiskResponsePolicy routeRisks;

    public CalendarIntentDraftPreflightResponsePolicy(
            CalendarRouteRiskResponsePolicy routeRisks) {
        this.routeRisks = routeRisks;
    }

    public String describe(
            CalendarIntentDraftPreflightService.PreflightResult preflight) {
        return details(preflight) + "\n\n" + question(preflight);
    }

    public String details(
            CalendarIntentDraftPreflightService.PreflightResult preflight) {
        if (!preflight.overlaps().isEmpty()) {
            StringBuilder result = new StringBuilder()
                    .append("⚠️ 本次行程尚未建立，因為這個安排會和 ")
                    .append(preflight.overlaps().size())
                    .append(" 個既有行程時間重疊：\n\n💥 衝突行程：");
            preflight.overlaps().forEach(item -> result.append("\n🏷️ ")
                    .append(item.title())
                    .append("\n🕒 ")
                    .append(placementSummary(item.placement()))
                    .append('\n'));
            return result.toString().stripTrailing();
        }
        if ("ROUTE_RISK".equals(preflight.status())) {
            return routeRisks.describe(preflight.assessments());
        }
        return "這筆提案和前後行程之間還缺可靠路線資料，我不能判定是否趕得上。";
    }

    public String question(
            CalendarIntentDraftPreflightService.PreflightResult preflight) {
        return PublicConversationChoiceRenderer.render(choiceQuestion(preflight));
    }

    public PublicConversationChoiceQuestion choiceQuestion(
            CalendarIntentDraftPreflightService.PreflightResult preflight) {
        return !preflight.overlaps().isEmpty()
                ? RouteCalendarChoiceCatalog.directOverlap()
                : RouteCalendarChoiceCatalog.scheduleConflict();
    }

    private static String placementSummary(CalendarPlacement placement) {
        return switch (placement) {
            case CalendarPlacement.TimedInterval interval ->
                interval.start().atZone(PUBLIC_ZONE).format(DATE_TIME)
                        + " ~ "
                        + interval.end().atZone(PUBLIC_ZONE).format(DATE_TIME);
            case CalendarPlacement.TimedPoint point ->
                point.time().atZone(PUBLIC_ZONE).format(DATE_TIME);
            case CalendarPlacement.AllDay allDay ->
                allDay.start() + " 全天";
        };
    }
}
