package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ExpenseCategory;
import org.junit.jupiter.api.Test;

class ConsumptionTagCatalogTest {

    private final ConsumptionTagCatalog catalog = new ConsumptionTagCatalog();

    @Test
    void classifiesRepresentativeLifeAndWorkExpensesDeterministically() {
        assertThat(catalog.classify("程式開發的書", "博客來").category())
                .isEqualTo(ExpenseCategory.EDUCATION);
        assertThat(catalog.classify("女兒游泳課", "運動中心").category())
                .isEqualTo(ExpenseCategory.CHILDCARE);
        assertThat(catalog.classify("高鐵票", "台灣高鐵").category())
                .isEqualTo(ExpenseCategory.TRANSPORT);
        assertThat(catalog.classify("張學友演唱會門票", "售票系統").category())
                .isEqualTo(ExpenseCategory.ENTERTAINMENT);
    }

    @Test
    void retainsMerchantAsOrganizationAndPromotionTag() {
        var result = catalog.classify("冷氣促銷", "全國電子");

        assertThat(result.category()).isEqualTo(ExpenseCategory.ELECTRONICS);
        assertThat(result.tags()).contains(
                "merchant:全國電子", "organization:全國電子", "activity:promotion");
    }

    @Test
    void unknownItemIsNotGuessed() {
        assertThat(catalog.classify("自訂項目甲", null).category())
                .isEqualTo(ExpenseCategory.UNKNOWN);
    }

    @Test
    void taxPaymentHasDedicatedQueryableCategory() {
        ConsumptionTagCatalog.Classification result =
                catalog.classify("房屋稅", "臺北市稅捐稽徵處");

        assertThat(result.category()).isEqualTo(ExpenseCategory.TAX);
        assertThat(result.tags()).contains("category:tax", "item:房屋稅");
    }

    @Test
    void genericDepositTransferIsStillAConsumptionRecord() {
        assertThat(catalog.classify("訂金", "迎新淨化科技有限公司").category())
                .isEqualTo(ExpenseCategory.OTHER);
    }

    @Test
    void explicitDailyFeeGetsPaymentKindWithoutTreatingCoffeeAsFee() {
        var utilities = catalog.classify("七月電費", "台電");

        assertThat(catalog.paymentKind("七月電費", "台電"))
                .contains(ConsumptionTagCatalog.PaymentKind.UTILITIES);
        assertThat(utilities.tags()).contains("activity:payment", "payment:utilities");
        assertThat(catalog.paymentKind("咖啡", "咖啡館")).isEmpty();
    }
}
