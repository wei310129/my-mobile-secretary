package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarRouteRiskResponsePolicy;
import org.springframework.stereotype.Component;

@Component
public class CalendarIntentDraftPreflightResponsePolicy {

    private final CalendarRouteRiskResponsePolicy routeRisks;

    public CalendarIntentDraftPreflightResponsePolicy(
            CalendarRouteRiskResponsePolicy routeRisks) {
        this.routeRisks = routeRisks;
    }

    public String describe(
            CalendarIntentDraftPreflightService.PreflightResult preflight) {
        if ("ROUTE_RISK".equals(preflight.status())) {
            return routeRisks.describe(preflight.assessments());
        }
        return "這筆提案和前後行程之間還缺可靠路線資料，我不能判定是否趕得上。要仍照這個版本建立嗎？";
    }
}
