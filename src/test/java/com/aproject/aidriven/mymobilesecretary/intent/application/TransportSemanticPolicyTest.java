package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransportSemanticPolicyTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "先不要直接建立行程",
        "這是間接建立的關聯",
        "連接會議室設備",
        "承接客戶會議"
    })
    void nonTransportCompoundsNeverBecomePickupResponsibility(String text) {
        assertThat(TransportSemanticPolicy.classify(text).responsibility())
                .isEqualTo(TransportSemanticPolicy.Responsibility.NONE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void actorBeneficiaryResponsibilityAndOccupancyAreTyped(
            String text,
            TransportSemanticPolicy.ActorRole actor,
            TransportSemanticPolicy.BeneficiaryRole beneficiary,
            TransportSemanticPolicy.Responsibility responsibility,
            TransportSemanticPolicy.Occupancy occupancy,
            boolean pointResponsibility) {
        TransportSemanticPolicy.Decision decision =
                TransportSemanticPolicy.classify(text);

        assertThat(decision.actor()).isEqualTo(actor);
        assertThat(decision.beneficiary()).isEqualTo(beneficiary);
        assertThat(decision.responsibility()).isEqualTo(responsibility);
        assertThat(decision.occupancy()).isEqualTo(occupancy);
        assertThat(decision.isPointResponsibility())
                .isEqualTo(pointResponsibility);
    }

    private static Stream<Arguments> scenarios() {
        return Stream.of(
                scenario(
                        "我送小宇去醫院",
                        TransportSemanticPolicy.ActorRole.SELF,
                        TransportSemanticPolicy.BeneficiaryRole.OTHER_PERSON,
                        TransportSemanticPolicy.Responsibility.DROP_OFF,
                        TransportSemanticPolicy.Occupancy.POINT_IN_TIME,
                        true),
                scenario(
                        "林先生載安安到陶藝課",
                        TransportSemanticPolicy.ActorRole.OTHER,
                        TransportSemanticPolicy.BeneficiaryRole.OTHER_PERSON,
                        TransportSemanticPolicy.Responsibility.DROP_OFF,
                        TransportSemanticPolicy.Occupancy.POINT_IN_TIME,
                        true),
                scenario(
                        "明早送同事到機場",
                        TransportSemanticPolicy.ActorRole.UNKNOWN,
                        TransportSemanticPolicy.BeneficiaryRole.OTHER_PERSON,
                        TransportSemanticPolicy.Responsibility.DROP_OFF,
                        TransportSemanticPolicy.Occupancy.POINT_IN_TIME,
                        true),
                scenario(
                        "校車送小明回家",
                        TransportSemanticPolicy.ActorRole.OTHER,
                        TransportSemanticPolicy.BeneficiaryRole.OTHER_PERSON,
                        TransportSemanticPolicy.Responsibility.DROP_OFF,
                        TransportSemanticPolicy.Occupancy.POINT_IN_TIME,
                        true),
                scenario(
                        "我去接客戶回公司",
                        TransportSemanticPolicy.ActorRole.SELF,
                        TransportSemanticPolicy.BeneficiaryRole.OTHER_PERSON,
                        TransportSemanticPolicy.Responsibility.PICK_UP,
                        TransportSemanticPolicy.Occupancy.POINT_IN_TIME,
                        true),
                scenario(
                        "十二點接同事下班",
                        TransportSemanticPolicy.ActorRole.UNKNOWN,
                        TransportSemanticPolicy.BeneficiaryRole.OTHER_PERSON,
                        TransportSemanticPolicy.Responsibility.PICK_UP,
                        TransportSemanticPolicy.Occupancy.POINT_IN_TIME,
                        true),
                scenario(
                        "下午三點接王先生",
                        TransportSemanticPolicy.ActorRole.UNKNOWN,
                        TransportSemanticPolicy.BeneficiaryRole.OTHER_PERSON,
                        TransportSemanticPolicy.Responsibility.PICK_UP,
                        TransportSemanticPolicy.Occupancy.POINT_IN_TIME,
                        true),
                scenario(
                        "送孩子上直排輪活動",
                        TransportSemanticPolicy.ActorRole.UNKNOWN,
                        TransportSemanticPolicy.BeneficiaryRole.OTHER_PERSON,
                        TransportSemanticPolicy.Responsibility.DROP_OFF,
                        TransportSemanticPolicy.Occupancy.POINT_IN_TIME,
                        true),
                scenario(
                        "媽媽送我去醫院",
                        TransportSemanticPolicy.ActorRole.OTHER,
                        TransportSemanticPolicy.BeneficiaryRole.SELF,
                        TransportSemanticPolicy.Responsibility.DROP_OFF,
                        TransportSemanticPolicy.Occupancy.FULL_INTERVAL,
                        false),
                scenario(
                        "我陪爸爸去診所",
                        TransportSemanticPolicy.ActorRole.SELF,
                        TransportSemanticPolicy.BeneficiaryRole.OTHER_PERSON,
                        TransportSemanticPolicy.Responsibility.ACCOMPANY,
                        TransportSemanticPolicy.Occupancy.UNKNOWN,
                        false),
                scenario(
                        "姐姐陪我去展覽",
                        TransportSemanticPolicy.ActorRole.OTHER,
                        TransportSemanticPolicy.BeneficiaryRole.SELF,
                        TransportSemanticPolicy.Responsibility.ACCOMPANY,
                        TransportSemanticPolicy.Occupancy.UNKNOWN,
                        false),
                scenario(
                        "我明天去上英文課",
                        TransportSemanticPolicy.ActorRole.SELF,
                        TransportSemanticPolicy.BeneficiaryRole.SELF,
                        TransportSemanticPolicy.Responsibility.ATTEND,
                        TransportSemanticPolicy.Occupancy.FULL_INTERVAL,
                        false),
                scenario(
                        "物流配送教材到英文課教室",
                        TransportSemanticPolicy.ActorRole.OTHER,
                        TransportSemanticPolicy.BeneficiaryRole.NON_PERSON,
                        TransportSemanticPolicy.Responsibility.DELIVERY,
                        TransportSemanticPolicy.Occupancy.POINT_IN_TIME,
                        true),
                scenario(
                        "把洗衣機送修後送文件去公司",
                        TransportSemanticPolicy.ActorRole.OTHER,
                        TransportSemanticPolicy.BeneficiaryRole.NON_PERSON,
                        TransportSemanticPolicy.Responsibility.DELIVERY,
                        TransportSemanticPolicy.Occupancy.POINT_IN_TIME,
                        true),
                scenario(
                        "明天寄送包裹到門市",
                        TransportSemanticPolicy.ActorRole.OTHER,
                        TransportSemanticPolicy.BeneficiaryRole.NON_PERSON,
                        TransportSemanticPolicy.Responsibility.DELIVERY,
                        TransportSemanticPolicy.Occupancy.POINT_IN_TIME,
                        true),
                scenario(
                        "我接著上日文課",
                        TransportSemanticPolicy.ActorRole.UNKNOWN,
                        TransportSemanticPolicy.BeneficiaryRole.UNKNOWN,
                        TransportSemanticPolicy.Responsibility.NONE,
                        TransportSemanticPolicy.Occupancy.UNKNOWN,
                        false),
                scenario(
                        "客服接收文件後回覆",
                        TransportSemanticPolicy.ActorRole.UNKNOWN,
                        TransportSemanticPolicy.BeneficiaryRole.UNKNOWN,
                        TransportSemanticPolicy.Responsibility.NONE,
                        TransportSemanticPolicy.Occupancy.UNKNOWN,
                        false),
                scenario(
                        "每週三固定意圖測試送課",
                        TransportSemanticPolicy.ActorRole.UNKNOWN,
                        TransportSemanticPolicy.BeneficiaryRole.UNKNOWN,
                        TransportSemanticPolicy.Responsibility.NONE,
                        TransportSemanticPolicy.Occupancy.UNKNOWN,
                        false),
                scenario(
                        "下午開會討論交通安排",
                        TransportSemanticPolicy.ActorRole.UNKNOWN,
                        TransportSemanticPolicy.BeneficiaryRole.UNKNOWN,
                        TransportSemanticPolicy.Responsibility.NONE,
                        TransportSemanticPolicy.Occupancy.UNKNOWN,
                        false));
    }

    private static Arguments scenario(
            String text,
            TransportSemanticPolicy.ActorRole actor,
            TransportSemanticPolicy.BeneficiaryRole beneficiary,
            TransportSemanticPolicy.Responsibility responsibility,
            TransportSemanticPolicy.Occupancy occupancy,
            boolean pointResponsibility) {
        return Arguments.of(
                text,
                actor,
                beneficiary,
                responsibility,
                occupancy,
                pointResponsibility);
    }
}
