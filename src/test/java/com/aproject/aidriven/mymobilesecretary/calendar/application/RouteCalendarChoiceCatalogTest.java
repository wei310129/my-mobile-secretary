package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationChoiceRenderer;
import org.junit.jupiter.api.Test;

class RouteCalendarChoiceCatalogTest {

    @Test
    void directOverlapUsesTheSameCatalogForNumberedDisplayAndAnswerResolution() {
        var question = RouteCalendarChoiceCatalog.directOverlap();

        assertThat(PublicConversationChoiceRenderer.render(question))
                .contains("\n\n1. 調整出發時間：", "\n\n2. 照原安排保留：");
        assertThat(question.resolveAction("1"))
                .contains(RouteCalendarChoiceCatalog.CHANGE_TIME);
        assertThat(question.resolveAction("第2項"))
                .contains(RouteCalendarChoiceCatalog.KEEP_ORIGINAL);
        assertThat(question.resolveAction("照原安排保留"))
                .contains(RouteCalendarChoiceCatalog.KEEP_ORIGINAL);
    }

    @Test
    void everyRouteMultiActionQuestionResolvesTheDisplayedOrdinalFromItsOwnCatalog() {
        assertThat(RouteCalendarChoiceCatalog.scheduleConflict().resolveAction("1"))
                .contains(RouteCalendarChoiceCatalog.ADJUST_SAFE);
        assertThat(RouteCalendarChoiceCatalog.providerUnavailable().resolveAction("2"))
                .contains(RouteCalendarChoiceCatalog.DISCARD);
        assertThat(RouteCalendarChoiceCatalog.transportOffer().resolveAction("1"))
                .contains(RouteCalendarChoiceCatalog.ACCEPT);
        assertThat(RouteCalendarChoiceCatalog.activityAdjustability().resolveAction("2"))
                .contains(RouteCalendarChoiceCatalog.WINDOWED);
        assertThat(RouteCalendarChoiceCatalog.activityAdjustability().resolveAction("3"))
                .contains(RouteCalendarChoiceCatalog.FLEXIBLE);
        assertThat(RouteCalendarChoiceCatalog.departureReminder(false).resolveAction("2"))
                .contains(RouteCalendarChoiceCatalog.DECLINE);
    }
}
