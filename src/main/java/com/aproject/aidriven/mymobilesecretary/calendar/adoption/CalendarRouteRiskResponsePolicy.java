package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CalendarRouteRiskResponsePolicy {

    public String describe(List<PersonalRouteAssessment> assessments) {
        List<PersonalRouteAssessment> safe = List.copyOf(assessments);
        if (safe.isEmpty()) {
            return "目前沒有兩個可銜接的定點行程。要先補上行程地點嗎？";
        }
        var insufficient = safe.stream()
                .filter(assessment -> assessment.status()
                        == PersonalRouteStatus.INSUFFICIENT_EVIDENCE)
                .findFirst();
        var risk = safe.stream()
                .filter(PersonalRouteAssessment::isRouteRisk)
                .findFirst();
        if (risk.isPresent()) {
            return riskMessage(risk.orElseThrow());
        }
        if (insufficient.isPresent()) {
            return "這趟和相鄰行程之間還缺可靠路線資料，我不能判定是否趕得上。這次要用哪種交通方式？";
        }
        return "目前相鄰行程都接得上。";
    }

    private static String riskMessage(PersonalRouteAssessment risk) {
        long gapMinutes = Math.max(0, risk.availableGap().toMinutes());
        long requiredMinutes = risk.requiredTravel().toMinutes();
        String preferred = preferredAdjustment(risk);
        return "這趟和相鄰行程之間只有 %d 分鐘，目前路線需要約 %d 分鐘，會接不上。%s要照原安排保留嗎？"
                .formatted(
                        gapMinutes,
                        requiredMinutes,
                        preferred);
    }

    private static String preferredAdjustment(PersonalRouteAssessment risk) {
        if (risk.to().adjustability() != Adjustability.LOCKED) {
            return "我建議先把後一段延後；如果不方便，再改交通方式。";
        }
        if (risk.from().adjustability() != Adjustability.LOCKED) {
            return "我建議先把前一段提早；如果不方便，再改交通方式。";
        }
        return "我建議先調整後一段時間；若兩段都不能動，再換更快的交通方式。";
    }
}
