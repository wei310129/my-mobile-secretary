package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.List;

/**
 * LLM 從收據照片解析出的結構化結果(AI 五層第 1 層的輸出)。
 * 與 IntentCommand 相同原則:LLM 輸出不直接信任,ReceiptService 逐欄驗證後才落地。
 *
 * @param storeName   店名(看得出來才填)
 * @param purchasedAt 消費日期 yyyy-MM-dd(看得出來才填;沒有就當今天)
 * @param items       品項清單(單價為台幣整數;讀不出價格的品項不要編)
 */
public record ReceiptCommand(
        String storeName,
        String purchasedAt,
        List<Line> items,
        DocumentType documentType,
        String documentTitle,
        List<ItineraryEntry> itineraryEntries,
        List<String> activities,
        List<String> notices,
        ContactCard contactCard,
        List<MenuEntry> menuEntries,
        BloodDonationInfo bloodDonationInfo,
        PaintProductInfo paintProductInfo,
        PaymentNoticeInfo paymentNoticeInfo,
        UtilityBillInfo utilityBillInfo,
        VenueVisitInfo venueVisitInfo
) {

    /** Backward-compatible full constructor used before venue information was added. */
    public ReceiptCommand(String storeName, String purchasedAt, List<Line> items,
                          DocumentType documentType, String documentTitle,
                          List<ItineraryEntry> itineraryEntries, List<String> activities,
                          List<String> notices, ContactCard contactCard,
                          List<MenuEntry> menuEntries, BloodDonationInfo bloodDonationInfo,
                          PaintProductInfo paintProductInfo, PaymentNoticeInfo paymentNoticeInfo,
                          UtilityBillInfo utilityBillInfo) {
        this(storeName, purchasedAt, items, documentType, documentTitle, itineraryEntries,
                activities, notices, contactCard, menuEntries, bloodDonationInfo,
                paintProductInfo, paymentNoticeInfo, utilityBillInfo, null);
    }

    /** Backward-compatible receipt constructor used by existing callers and tests. */
    public ReceiptCommand(String storeName, String purchasedAt, List<Line> items) {
        this(storeName, purchasedAt, items,
                items == null || items.isEmpty() ? DocumentType.UNKNOWN : DocumentType.RECEIPT,
                null, List.of(), List.of(), List.of(), null, List.of(), null, null, null, null);
    }

    /** Backward-compatible document constructor used by existing services and tests. */
    public ReceiptCommand(String storeName, String purchasedAt, List<Line> items,
                          DocumentType documentType, String documentTitle,
                          List<ItineraryEntry> itineraryEntries, List<String> activities,
                          List<String> notices) {
        this(storeName, purchasedAt, items, documentType, documentTitle,
                itineraryEntries, activities, notices, null, List.of(), null, null, null, null);
    }

    /** Backward-compatible constructor with contact-card fields but no school menu. */
    public ReceiptCommand(String storeName, String purchasedAt, List<Line> items,
                          DocumentType documentType, String documentTitle,
                          List<ItineraryEntry> itineraryEntries, List<String> activities,
                          List<String> notices, ContactCard contactCard) {
        this(storeName, purchasedAt, items, documentType, documentTitle,
                itineraryEntries, activities, notices, contactCard, List.of(), null, null, null, null);
    }

    /** Backward-compatible constructor with menu fields but no donation record. */
    public ReceiptCommand(String storeName, String purchasedAt, List<Line> items,
                          DocumentType documentType, String documentTitle,
                          List<ItineraryEntry> itineraryEntries, List<String> activities,
                          List<String> notices, ContactCard contactCard,
                          List<MenuEntry> menuEntries) {
        this(storeName, purchasedAt, items, documentType, documentTitle,
                itineraryEntries, activities, notices, contactCard, menuEntries, null, null, null, null);
    }

    /** Backward-compatible constructor with donation fields but no product observation. */
    public ReceiptCommand(String storeName, String purchasedAt, List<Line> items,
                          DocumentType documentType, String documentTitle,
                          List<ItineraryEntry> itineraryEntries, List<String> activities,
                          List<String> notices, ContactCard contactCard,
                          List<MenuEntry> menuEntries, BloodDonationInfo bloodDonationInfo) {
        this(storeName, purchasedAt, items, documentType, documentTitle, itineraryEntries,
                activities, notices, contactCard, menuEntries, bloodDonationInfo, null, null, null);
    }

    /** Backward-compatible constructor with product fields but no payment notice. */
    public ReceiptCommand(String storeName, String purchasedAt, List<Line> items,
                          DocumentType documentType, String documentTitle,
                          List<ItineraryEntry> itineraryEntries, List<String> activities,
                          List<String> notices, ContactCard contactCard,
                          List<MenuEntry> menuEntries, BloodDonationInfo bloodDonationInfo,
                          PaintProductInfo paintProductInfo) {
        this(storeName, purchasedAt, items, documentType, documentTitle, itineraryEntries,
                activities, notices, contactCard, menuEntries, bloodDonationInfo,
                paintProductInfo, null, null);
    }

    /** Backward-compatible constructor with payment notice but no utility history. */
    public ReceiptCommand(String storeName, String purchasedAt, List<Line> items,
                          DocumentType documentType, String documentTitle,
                          List<ItineraryEntry> itineraryEntries, List<String> activities,
                          List<String> notices, ContactCard contactCard,
                          List<MenuEntry> menuEntries, BloodDonationInfo bloodDonationInfo,
                          PaintProductInfo paintProductInfo, PaymentNoticeInfo paymentNoticeInfo) {
        this(storeName, purchasedAt, items, documentType, documentTitle, itineraryEntries,
                activities, notices, contactCard, menuEntries, bloodDonationInfo,
                paintProductInfo, paymentNoticeInfo, null);
    }

    public enum DocumentType {
        RECEIPT,
        TRAVEL_ITINERARY,
        EVENT_POSTER,
        EVENT_REGISTRATION,
        MEDICAL_APPOINTMENT,
        WORK_SCHOOL_SUSPENSION,
        BUSINESS_CARD,
        TAX_PAYMENT,
        BANK_TRANSFER,
        SCHOOL_MENU,
        BLOOD_DONATION_RECORD,
        PAINT_PRODUCT,
        PAYMENT_NOTICE,
        UTILITY_BILL_HISTORY,
        VENUE_VISIT_INFO,
        UNKNOWN
    }

    /**
     * 收據上的一行品項。
     *
     * @param name     品項名
     * @param price    單價(台幣,整數)
     * @param quantity 數量(沒印就 1)
     */
    public record Line(String name, Integer price, Integer quantity) {
    }

    /** A printed itinerary line. Missing values stay null; the model must never infer times. */
    public record ItineraryEntry(
            String date,
            String startTime,
            String endTime,
            String title,
            String placeName,
            String details
    ) {
    }

    /** Explicitly visible business-card fields. Missing values remain null/empty. */
    public record ContactCard(
            String displayName,
            String organizationName,
            String profession,
            List<String> phoneNumbers,
            List<String> emails,
            String address
    ) {
    }

    /** One explicit date/meal row copied from a school menu image. */
    public record MenuEntry(String date, String mealType, List<String> items) {
    }

    /** Explicit, non-identifying facts copied from a blood-donation record image. */
    public record BloodDonationInfo(
            String donationDate,
            String donationLocation,
            String nextEligibleDate
    ) {
    }

    /** Visible package-label fields only; an image cannot establish usage or adverse reaction. */
    public record PaintProductInfo(
            String productName,
            String brandName,
            String colorName,
            String colorCode,
            List<String> publicTags
    ) {
        public PaintProductInfo(String productName, String brandName,
                                String colorName, String colorCode) {
            this(productName, brandName, colorName, colorCode, List.of());
        }
    }

    /** Visible non-sensitive fields from a bill or payment notice that has not been paid. */
    public record PaymentNoticeInfo(
            String title,
            String issuer,
            String dueDate,
            Integer amountTwd
    ) {
    }

    /** Visible, non-identifying monthly utility history rows. */
    public record UtilityBillInfo(
            String provider,
            List<UtilityBillEntry> entries
    ) {
    }

    /** billingMonth may be ROC (113/03) or Gregorian (2024-03); Java validates conversion. */
    public record UtilityBillEntry(
            String billingMonth,
            Integer usageKwh,
            Integer amountTwd
    ) {
    }

    /** Explicit facts from a venue sign; QR payloads are never inferred or decoded by the model. */
    public record VenueVisitInfo(
            String venueName,
            String subject,
            String details,
            Boolean reservationRequired,
            Integer minimumGroupSize
    ) {
    }
}
