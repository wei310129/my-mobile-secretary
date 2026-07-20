package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ExpenseCategory;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic seed taxonomy for consumption records.
 *
 * <p>The catalog deliberately does not call an LLM. Unknown items stay UNKNOWN and can be
 * classified later; a merchant is retained as both a merchant and an organization tag so the
 * same canonical name can eventually be shared by schedule and promotion indexes.</p>
 */
@Component
public class ConsumptionTagCatalog {

    private static final List<CategoryRule> RULES = List.of(
            rule(ExpenseCategory.TAX, "稅", "稅款", "繳稅", "所得稅", "房屋稅", "地價稅", "牌照稅"),
            rule(ExpenseCategory.CHILDCARE, "育兒", "奶粉", "尿布", "嬰兒", "托嬰", "幼兒", "小孩學費", "游泳課"),
            rule(ExpenseCategory.EDUCATION, "課本", "教科書", "書", "課程", "學費", "補習", "教材"),
            rule(ExpenseCategory.TRANSPORT, "高鐵", "火車", "捷運", "公車", "船票", "船", "飛機", "機票", "計程車", "uber", "油錢", "停車"),
            rule(ExpenseCategory.BEVERAGE, "手搖", "飲料", "茶", "咖啡", "牛奶", "鮮奶", "果汁", "啤酒"),
            rule(ExpenseCategory.FOOD, "麥當勞", "鍋貼", "便當", "早餐", "午餐", "晚餐", "餐", "麵", "飯", "漢堡", "食品"),
            rule(ExpenseCategory.HOUSEHOLD, "生活用品", "衛生紙", "洗衣精", "清潔", "日用品", "垃圾袋", "牙膏"),
            rule(ExpenseCategory.ENTERTAINMENT, "演唱會", "電影", "威秀", "門票", "遊戲", "展覽", "娛樂"),
            rule(ExpenseCategory.LUXURY, "精品", "包包", "首飾", "珠寶", "鑽石", "名牌"),
            rule(ExpenseCategory.ELECTRONICS, "全國電子", "電器", "家電", "冷氣", "電腦", "手機", "螢幕", "耳機"),
            rule(ExpenseCategory.HEALTHCARE, "藥", "診所", "醫院", "醫療", "保健", "看診"),
            rule(ExpenseCategory.CLOTHING, "衣服", "服飾", "鞋", "外套", "褲"),
            rule(ExpenseCategory.HOUSING, "房租", "家具", "修繕", "水電", "居家"),
            rule(ExpenseCategory.WORK, "程式開發", "辦公", "公司用品", "報帳"),
            rule(ExpenseCategory.OTHER, "訂金", "預付款", "轉帳", "匯款"));

    private static final List<PaymentRule> PAYMENT_RULES = List.of(
            payment(PaymentKind.UTILITIES, "水費", "電費", "瓦斯費", "天然氣費", "水電費"),
            payment(PaymentKind.EDUCATION, "學費", "註冊費", "托育費", "幼兒園費", "補習費"),
            payment(PaymentKind.PARKING, "停車費"),
            payment(PaymentKind.HOUSING, "管理費", "房租", "租金"),
            payment(PaymentKind.TELECOM, "電信費", "網路費", "電話費", "手機費"),
            payment(PaymentKind.INSURANCE, "保險費", "保費"),
            payment(PaymentKind.TAX, "繳稅", "所得稅", "房屋稅", "地價稅", "牌照稅"),
            payment(PaymentKind.MEDICAL, "掛號費", "醫療費", "診療費"),
            payment(PaymentKind.TRANSPORT, "通行費", "過路費", "etc"),
            payment(PaymentKind.TRANSFER, "訂金", "預付款", "轉帳", "匯款"));

    public Classification classify(String itemName, String merchantName) {
        String searchable = normalize(itemName) + " " + normalize(merchantName);
        ExpenseCategory category = RULES.stream()
                .filter(rule -> rule.keywords().stream().anyMatch(searchable::contains))
                .map(CategoryRule::category)
                .findFirst()
                .orElse(ExpenseCategory.UNKNOWN);

        Set<String> tags = new LinkedHashSet<>();
        tags.add("domain:consumption");
        tags.add("category:" + category.name().toLowerCase(Locale.ROOT));
        addNamedTag(tags, "item:", itemName);
        addNamedTag(tags, "merchant:", merchantName);
        addNamedTag(tags, "organization:", merchantName);
        if (searchable.contains("促銷") || searchable.contains("特價")
                || searchable.contains("優惠") || searchable.contains("折扣")) {
            tags.add("activity:promotion");
        }
        paymentKind(itemName, merchantName).ifPresent(kind -> {
            tags.add("activity:payment");
            tags.add("payment:" + kind.name().toLowerCase(Locale.ROOT));
        });
        return new Classification(category, Set.copyOf(tags));
    }

    /** Classifies only explicit payment words; it never turns an unpaid notice into a transaction. */
    public Optional<PaymentKind> paymentKind(String itemName, String merchantName) {
        String item = normalize(itemName);
        String searchable = item + " " + normalize(merchantName);
        Optional<PaymentKind> explicit = PAYMENT_RULES.stream()
                .filter(rule -> rule.keywords().stream().anyMatch(searchable::contains))
                .map(PaymentRule::kind)
                .findFirst();
        if (explicit.isPresent()) return explicit;
        return item.endsWith("費") || item.contains("費用")
                ? Optional.of(PaymentKind.OTHER_FEE) : Optional.empty();
    }

    public ExpenseCategory parseCategory(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = normalize(value);
        for (ExpenseCategory category : ExpenseCategory.values()) {
            if (category.name().equalsIgnoreCase(value.strip())
                    || normalize(category.displayName()).equals(normalized)) {
                return category;
            }
        }
        return RULES.stream()
                .filter(rule -> rule.keywords().contains(normalized))
                .map(CategoryRule::category)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported expense category: " + value));
    }

    private static CategoryRule rule(ExpenseCategory category, String... keywords) {
        return new CategoryRule(category, List.of(keywords).stream().map(ConsumptionTagCatalog::normalize).toList());
    }

    private static PaymentRule payment(PaymentKind kind, String... keywords) {
        return new PaymentRule(kind, List.of(keywords).stream()
                .map(ConsumptionTagCatalog::normalize).toList());
    }

    private static void addNamedTag(Set<String> tags, String prefix, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String safe = value.strip().replace("|", " ").replace("\n", " ").replace("\r", " ");
        tags.add(prefix + safe);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    public record Classification(ExpenseCategory category, Set<String> tags) {
    }

    public enum PaymentKind {
        UTILITIES("水電瓦斯"), EDUCATION("學費教育"), PARKING("停車"), HOUSING("居住"),
        TELECOM("電信網路"), INSURANCE("保險"), TAX("稅款"), MEDICAL("醫療"),
        TRANSPORT("交通通行"), TRANSFER("轉帳預付款"), OTHER_FEE("其他費用");

        private final String displayName;

        PaymentKind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private record CategoryRule(ExpenseCategory category, List<String> keywords) {
    }

    private record PaymentRule(PaymentKind kind, List<String> keywords) {
    }
}
