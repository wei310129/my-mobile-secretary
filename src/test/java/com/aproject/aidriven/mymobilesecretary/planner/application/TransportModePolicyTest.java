package com.aproject.aidriven.mymobilesecretary.planner.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest.TravelMode;
import org.junit.jupiter.api.Test;

class TransportModePolicyTest {

    @Test
    void resolvesBoundedModeEvidenceWithoutPersonPlaceOrBrandBranches() {
        assertResolved("我從公司開車去醫院", TravelMode.DRIVE);
        assertResolved("要騎機車到車站", TravelMode.TWO_WHEELER);
        assertResolved("走路去新店區公所", TravelMode.WALK);
        assertResolved("搭高鐵到左營", TravelMode.TRANSIT);
        assertThat(TransportModePolicy.resolve("Google 幫我算去台北"))
                .extracting(TransportModePolicy.Resolution::status)
                .isEqualTo(TransportModePolicy.Status.MISSING);
    }

    @Test
    void missingOrMultipleModesFailClosed() {
        assertThat(TransportModePolicy.resolve("幫我算去醫院要多久").status())
                .isEqualTo(TransportModePolicy.Status.MISSING);
        assertThat(TransportModePolicy.resolve("我說騎車但沒說是機車還是腳踏車").status())
                .isEqualTo(TransportModePolicy.Status.AMBIGUOUS);
        assertThat(TransportModePolicy.resolve("開車或搭捷運都可以").status())
                .isEqualTo(TransportModePolicy.Status.AMBIGUOUS);
    }

    private static void assertResolved(String text, TravelMode expected) {
        var resolution = TransportModePolicy.resolve(text);
        assertThat(resolution.status()).isEqualTo(TransportModePolicy.Status.RESOLVED);
        assertThat(resolution.mode()).isEqualTo(expected);
    }
}
