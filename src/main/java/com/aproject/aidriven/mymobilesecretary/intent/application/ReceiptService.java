package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.contact.application.ExternalContactService;
import com.aproject.aidriven.mymobilesecretary.health.application.BloodDonationService;
import com.aproject.aidriven.mymobilesecretary.health.domain.BloodDonationRecord.SourceType;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ProductExperienceService;
import com.aproject.aidriven.mymobilesecretary.payment.application.BankTransferService;
import com.aproject.aidriven.mymobilesecretary.payment.application.PaymentNoticeService;
import com.aproject.aidriven.mymobilesecretary.schoolmeal.application.SchoolMealService;
import com.aproject.aidriven.mymobilesecretary.shared.security.PromptInjectionGuard;
import com.aproject.aidriven.mymobilesecretary.utility.application.UtilityBillService;
import com.aproject.aidriven.mymobilesecretary.venue.application.VenueVisitInformationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 收據編排:照片 → LLM 解析 → 逐行驗證 → 價格歷史入庫 → 回覆摘要。
 *
 * 可靠度鐵律的收據版:LLM 失敗或照片不是收據,回明確訊息請使用者改用文字,
 * 絕不讓例外往 webhook 洩漏;單行資料爛掉只跳過該行,好的照存。
 */
@Service
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    /** 單張收據最多入庫行數:LLM 幻覺出上百行時的保險絲。 */
    private static final int MAX_LINES = 50;

    private final ObjectProvider<ReceiptInterpreter> interpreterProvider;
    private final PriceRecordService priceRecordService;
    private final Clock clock;
    private com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService
            travelItineraryDraftService;
    private TravelItineraryDraftAnswerService travelItineraryDraftAnswerService;
    private com.aproject.aidriven.mymobilesecretary.event.application.EventIntakeService
            eventIntakeService;
    private com.aproject.aidriven.mymobilesecretary.safety.application.WorkSchoolSuspensionService
            suspensionService;
    private ExternalContactService externalContactService;
    private BankTransferService bankTransferService;
    private SchoolMealService schoolMealService;
    private BloodDonationService bloodDonationService;
    private ProductExperienceService productExperienceService;
    private PaymentNoticeService paymentNoticeService;
    private UtilityBillService utilityBillService;
    private VenueVisitInformationService venueVisitInformationService;

    public ReceiptService(ObjectProvider<ReceiptInterpreter> interpreterProvider,
                          PriceRecordService priceRecordService,
                          Clock clock) {
        this.interpreterProvider = interpreterProvider;
        this.priceRecordService = priceRecordService;
        this.clock = clock;
    }

    /** Optional setters keep direct-construction receipt unit tests source-compatible. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTravelItineraryServices(
            com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService
                    draftService,
            TravelItineraryDraftAnswerService answerService) {
        this.travelItineraryDraftService = draftService;
        this.travelItineraryDraftAnswerService = answerService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEventIntakeService(
            com.aproject.aidriven.mymobilesecretary.event.application.EventIntakeService service) {
        this.eventIntakeService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSuspensionService(
            com.aproject.aidriven.mymobilesecretary.safety.application.WorkSchoolSuspensionService service) {
        this.suspensionService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setExternalContactService(ExternalContactService service) {
        this.externalContactService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBankTransferService(BankTransferService service) {
        this.bankTransferService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSchoolMealService(SchoolMealService service) {
        this.schoolMealService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBloodDonationService(BloodDonationService service) {
        this.bloodDonationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProductExperienceService(ProductExperienceService service) {
        this.productExperienceService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPaymentNoticeService(PaymentNoticeService service) {
        this.paymentNoticeService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUtilityBillService(UtilityBillService service) {
        this.utilityBillService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setVenueVisitInformationService(VenueVisitInformationService service) {
        this.venueVisitInformationService = service;
    }

    /**
     * 處理一張收據照片,回傳要回覆給使用者的結果。
     *
     * @return 一定有人類可讀 message;savedCount = 實際入庫行數
     */
    public ReceiptResult handleImage(byte[] imageBytes, String mimeType) {
        ReceiptInterpreter interpreter = interpreterProvider.getIfAvailable();
        if (interpreter == null) {
            return new ReceiptResult("收據解析未啟用,請改用文字告訴我品項與價格。", 0);
        }

        ReceiptCommand command;
        try {
            command = interpreter.interpret(imageBytes, mimeType);
        } catch (Exception e) {
            // Image/model failures can repeat on webhook retries; avoid large stack-trace writes.
            log.warn("Image document interpretation failed ({})", e.getClass().getSimpleName());
            return new ReceiptResult("這張照片我解析不了,可以再拍清楚一點,或改用文字告訴我。", 0);
        }

        if (command == null) {
            return new ReceiptResult("這張照片看起來不是收據,或讀不到品項。", 0);
        }

        if (containsPromptInjection(command)) {
            log.warn("Untrusted image document rejected [reason=prompt_injection_signal]");
            return new ReceiptResult("DOCUMENT_SECURITY_REJECTED",
                    "圖片含有疑似指令或要求揭露敏感資訊的文字，為了安全沒有執行或儲存任何內容。", 0);
        }

        ReceiptCommand.DocumentType type = command.documentType();
        if (type == null) {
            type = command.items() == null || command.items().isEmpty()
                    ? ReceiptCommand.DocumentType.UNKNOWN : ReceiptCommand.DocumentType.RECEIPT;
        }
        if (type == ReceiptCommand.DocumentType.TRAVEL_ITINERARY) {
            return handleTravelItinerary(command);
        }
        if (type == ReceiptCommand.DocumentType.EVENT_POSTER) {
            return handleEventPoster(command);
        }
        if (type == ReceiptCommand.DocumentType.EVENT_REGISTRATION) {
            return handleEventRegistration(command);
        }
        if (type == ReceiptCommand.DocumentType.MEDICAL_APPOINTMENT) {
            return handleMedicalAppointment(command);
        }
        if (type == ReceiptCommand.DocumentType.WORK_SCHOOL_SUSPENSION) {
            return handleWorkSchoolSuspension(command);
        }
        if (type == ReceiptCommand.DocumentType.BUSINESS_CARD) {
            return handleBusinessCard(command);
        }
        if (type == ReceiptCommand.DocumentType.TAX_PAYMENT) {
            return handleTaxPayment(command);
        }
        if (type == ReceiptCommand.DocumentType.BANK_TRANSFER) {
            return handleBankTransfer(command);
        }
        if (type == ReceiptCommand.DocumentType.SCHOOL_MENU) {
            return handleSchoolMenu(command);
        }
        if (type == ReceiptCommand.DocumentType.BLOOD_DONATION_RECORD) {
            return handleBloodDonation(command);
        }
        if (type == ReceiptCommand.DocumentType.PAINT_PRODUCT) {
            return handlePaintProduct(command);
        }
        if (type == ReceiptCommand.DocumentType.PAYMENT_NOTICE) {
            return handlePaymentNotice(command);
        }
        if (type == ReceiptCommand.DocumentType.UTILITY_BILL_HISTORY) {
            return handleUtilityBillHistory(command);
        }
        if (type == ReceiptCommand.DocumentType.VENUE_VISIT_INFO) {
            return handleVenueVisitInformation(command);
        }
        if (type == ReceiptCommand.DocumentType.UNKNOWN) {
            return new ReceiptResult("UNKNOWN_IMAGE",
                    "我目前無法確定這張圖片的類型。請告訴我它是什麼，以及要保存或處理哪些資訊；我不會只憑圖片背景猜用途。", 0);
        }
        if (command.items() == null || command.items().isEmpty()) {
            return new ReceiptResult("RECEIPT_NOT_READABLE",
                    "這張照片看起來是收據，但讀不到有效品項。", 0);
        }

        // LLM 輸出逐行驗證:名稱/價格爛的跳過,不讓一行壞資料毀掉整張收據
        LocalDate purchasedAt = parseDateOrToday(command.purchasedAt());
        List<String> savedNames = new ArrayList<>();
        for (ReceiptCommand.Line line : command.items().stream().limit(MAX_LINES).toList()) {
            if (line == null || line.name() == null || line.name().isBlank()
                    || line.price() == null || line.price() <= 0) {
                continue;
            }
            try {
                int quantity = line.quantity() == null || line.quantity() <= 0
                        ? 1 : line.quantity();
                priceRecordService.record(
                        line.name(), command.storeName(), line.price(), quantity, purchasedAt);
                savedNames.add(line.name());
            } catch (Exception e) {
                log.warn("Price record rejected [cause={}]", e.getClass().getSimpleName());
            }
        }

        if (savedNames.isEmpty()) {
            return new ReceiptResult("收據上讀不到有效的品項價格。", 0);
        }
        String store = command.storeName() == null || command.storeName().isBlank()
                ? "" : "(" + command.storeName() + ")";
        return new ReceiptResult(
                "已記下 %d 項價格%s:\n%s".formatted(savedNames.size(), store,
                        String.join("\n", savedNames.stream().limit(5).toList())
                                + (savedNames.size() > 5 ? "\n……其餘品項也已記錄" : "")),
                savedNames.size());
    }

    private ReceiptResult handleTravelItinerary(ReceiptCommand command) {
        if (travelItineraryDraftService == null || travelItineraryDraftAnswerService == null) {
            return new ReceiptResult("TRAVEL_ITINERARY_UNAVAILABLE",
                    "旅行行程表辨識目前未啟用，請先改用文字提供行程。", 0);
        }
        try {
            var draft = travelItineraryDraftService.create(command);
            return new ReceiptResult("TRAVEL_ITINERARY_DRAFTED",
                    travelItineraryDraftAnswerService.previewMessage(draft), 0);
        } catch (IllegalArgumentException e) {
            log.warn("Travel itinerary image rejected ({})", e.getClass().getSimpleName());
            return new ReceiptResult("TRAVEL_ITINERARY_NOT_READABLE",
                    "這張圖片像旅行行程表，但讀不到可核對的行程、活動或注意事項。", 0);
        }
    }

    private ReceiptResult handleEventPoster(ReceiptCommand command) {
        if (eventIntakeService == null) {
            return new ReceiptResult("EVENT_POSTER_UNAVAILABLE",
                    "活動圖片辨識目前未啟用，請先貼上活動名稱、日期、時間與地點。", 0);
        }
        ReceiptCommand.ItineraryEntry event = command.itineraryEntries() == null
                ? null : command.itineraryEntries().stream()
                        .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (event == null) {
            return new ReceiptResult("EVENT_POSTER_NOT_READABLE",
                    "這張圖片像活動海報，但讀不到可核對的活動日期或地點。請貼官方文字資訊。", 0);
        }
        IntentResult result = eventIntakeService.ingestImageEvent(
                command.documentTitle() == null ? event.title() : command.documentTitle(),
                event.date(), event.startTime(), event.endTime(), event.placeName(),
                event.details(), () -> { });
        return new ReceiptResult("EVENT_POSTER_DRAFTED", result.message(), 0);
    }

    private ReceiptResult handleEventRegistration(ReceiptCommand command) {
        if (eventIntakeService == null) {
            return new ReceiptResult("EVENT_REGISTRATION_UNAVAILABLE",
                    "活動報名成功圖片處理目前未啟用，請先提供活動名稱、日期與時間。", 0);
        }
        ReceiptCommand.ItineraryEntry event = command.itineraryEntries() == null
                ? null : command.itineraryEntries().stream()
                        .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (event == null) {
            return new ReceiptResult("EVENT_REGISTRATION_NOT_READABLE",
                    "這張圖片像活動報名成功資訊，但讀不到活動名稱或日期時間。請重拍或補上文字。", 0);
        }
        String title = command.documentTitle() == null ? event.title() : command.documentTitle();
        int savedExpenses = 0;
        ReceiptCommand.Line fee = command.items() == null ? null : command.items().stream()
                .filter(java.util.Objects::nonNull)
                .filter(line -> line.price() != null && line.price() > 0)
                .findFirst().orElse(null);
        if (fee != null) {
            priceRecordService.record("活動報名：" + title, command.storeName(), fee.price(), 1,
                    parseDateOrToday(command.purchasedAt()));
            savedExpenses = 1;
        }
        IntentResult calendar = eventIntakeService.ingestRegisteredEvent(
                title, normalizeDocumentDate(event.date()), event.startTime(), event.endTime(),
                event.placeName(), event.details(), () -> { });
        String expense = savedExpenses == 1
                ? "已另存一筆明確報名費消費紀錄。"
                : "圖片沒有可核對的實付報名費，因此未捏造金額消費紀錄。";
        return new ReceiptResult("EVENT_REGISTRATION_IMPORTED",
                calendar.message() + "\n" + expense, savedExpenses);
    }

    private ReceiptResult handleVenueVisitInformation(ReceiptCommand command) {
        if (venueVisitInformationService == null) {
            return new ReceiptResult("VENUE_VISIT_INFO_UNAVAILABLE",
                    "場館參觀資訊保存目前未啟用；請先用文字告訴我場館名稱與要記住的內容。", 0);
        }
        ReceiptCommand.VenueVisitInfo info = command.venueVisitInfo();
        if (info == null || info.subject() == null || info.subject().isBlank()
                || info.details() == null || info.details().isBlank()) {
            return new ReceiptResult("VENUE_VISIT_INFO_NOT_READABLE",
                    "這張圖片可能是場館參觀資訊，但讀不到可核對的展示區或參觀規則。請告訴我圖片內容與場館名稱。", 0);
        }
        IntentResult result = venueVisitInformationService.ingestImage(
                info.subject(), info.venueName(), info.details(),
                Boolean.TRUE.equals(info.reservationRequired()), info.minimumGroupSize());
        return new ReceiptResult("VENUE_VISIT_INFO_CAPTURED", result.message(), 0);
    }

    private String normalizeDocumentDate(String value) {
        return parseDocumentDate(value).map(LocalDate::toString).orElse(null);
    }

    private ReceiptResult handleMedicalAppointment(ReceiptCommand command) {
        if (eventIntakeService == null) {
            return new ReceiptResult("MEDICAL_APPOINTMENT_UNAVAILABLE",
                    "掛號單圖片辨識目前未啟用，請先提供看診日期、時間、醫院、科別與醫師。", 0);
        }
        ReceiptCommand.ItineraryEntry appointment = command.itineraryEntries() == null
                ? null : command.itineraryEntries().stream()
                        .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (appointment == null) {
            return new ReceiptResult("MEDICAL_APPOINTMENT_NOT_READABLE",
                    "這張圖片像掛號或看診預約單，但讀不到可核對的日期、時間或院所。請重拍清楚一點或補上文字。", 0);
        }
        IntentResult result = eventIntakeService.ingestImageEvent(
                command.documentTitle() == null ? appointment.title() : command.documentTitle(),
                appointment.date(), appointment.startTime(), appointment.endTime(),
                appointment.placeName(), null, () -> { });
        return new ReceiptResult("MEDICAL_APPOINTMENT_DRAFTED",
                "已辨識為醫療掛號／看診單，只保存排程必要欄位。\n" + result.message(), 0);
    }

    private ReceiptResult handleWorkSchoolSuspension(ReceiptCommand command) {
        if (suspensionService == null) {
            return new ReceiptResult("WORK_SCHOOL_SUSPENSION_UNAVAILABLE",
                    "停班停課圖片辨識目前未啟用，請先用文字提供日期與縣市。", 0);
        }
        List<ReceiptCommand.ItineraryEntry> entries = command.itineraryEntries() == null
                ? List.of() : command.itineraryEntries().stream()
                        .filter(java.util.Objects::nonNull).toList();
        LocalDate date = entries.stream().map(ReceiptCommand.ItineraryEntry::date)
                .map(this::parseDocumentDate).flatMap(java.util.Optional::stream)
                .findFirst().orElse(null);
        List<com.aproject.aidriven.mymobilesecretary.safety.application.WorkSchoolSuspensionService.RegionStatus>
                statuses = entries.stream()
                        .map(entry -> new com.aproject.aidriven.mymobilesecretary.safety.application
                                .WorkSchoolSuspensionService.RegionStatus(
                                entry.title(), entry.details()))
                        .toList();
        try {
            IntentResult result = suspensionService.ingestImage(date, statuses);
            return new ReceiptResult("WORK_SCHOOL_SUSPENSION_DRAFTED", result.message(), 0);
        } catch (IllegalArgumentException invalidImage) {
            return new ReceiptResult("WORK_SCHOOL_SUSPENSION_NOT_READABLE",
                    "這張圖片像停班停課資訊，但讀不到可核對的日期、縣市與狀態。請重拍清楚一點或補上文字。", 0);
        }
    }

    private ReceiptResult handleBusinessCard(ReceiptCommand command) {
        if (externalContactService == null) {
            return new ReceiptResult("BUSINESS_CARD_UNAVAILABLE",
                    "名片聯絡人功能目前未啟用；原始圖片仍已保存。", 0);
        }
        try {
            ExternalContactService.ImportResult imported =
                    externalContactService.importBusinessCard(command.contactCard());
            var contact = imported.contact();
            String identity = contact.getProfession() == null
                    ? contact.getDisplayName()
                    : contact.getDisplayName() + "（" + contact.getProfession() + "）";
            String verb = imported.created() ? "已建立" : "已更新既有";
            return new ReceiptResult("BUSINESS_CARD_IMPORTED",
                    verb + "名片聯絡人：" + identity
                            + "。電話、Email 與地址已私密保存，不在回覆中完整重述。", 1);
        } catch (IllegalArgumentException unreadable) {
            return new ReceiptResult("BUSINESS_CARD_NOT_READABLE",
                    "這張圖片像名片，但姓名、公司或專業類別都無法可靠辨識；原圖已保存，請補充至少一項身分資訊。", 0);
        }
    }

    private ReceiptResult handleTaxPayment(ReceiptCommand command) {
        LocalDate paidAt;
        try {
            paidAt = command.purchasedAt() == null ? null : LocalDate.parse(command.purchasedAt());
        } catch (java.time.DateTimeException invalidDate) {
            paidAt = null;
        }
        ReceiptCommand.Line tax = command.items() == null ? null : command.items().stream()
                .filter(java.util.Objects::nonNull)
                .filter(line -> line.name() != null && !line.name().isBlank()
                        && line.price() != null && line.price() > 0)
                .findFirst().orElse(null);
        if (paidAt == null || tax == null) {
            return new ReceiptResult("TAX_PAYMENT_NEEDS_DETAILS",
                    "已辨識為繳稅紀錄，但實際繳納日期、稅目或實繳金額不完整，因此尚未建立消費紀錄；請補充缺少資訊。", 0);
        }
        priceRecordService.record(tax.name(), command.storeName(), tax.price(), 1, paidAt);
        return new ReceiptResult("TAX_PAYMENT_IMPORTED",
                "已記錄繳稅消費：%s，NT$ %,d，繳納日 %s。"
                        .formatted(tax.name(), tax.price(), paidAt), 1);
    }

    private ReceiptResult handleBankTransfer(ReceiptCommand command) {
        if (bankTransferService == null) {
            return new ReceiptResult("BANK_TRANSFER_UNAVAILABLE",
                    "轉帳消費功能目前未啟用；原始圖片仍已保存。", 0);
        }
        LocalDate transferredAt;
        try {
            transferredAt = command.purchasedAt() == null
                    ? null : LocalDate.parse(command.purchasedAt());
        } catch (java.time.DateTimeException invalidDate) {
            transferredAt = null;
        }
        ReceiptCommand.Line line = command.items() == null ? null : command.items().stream()
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        String purpose = line == null || line.name() == null || line.name().isBlank()
                ? "轉帳" : line.name().strip();
        Integer amount = line == null || line.price() == null || line.price() <= 0
                ? null : line.price();
        String recipient = command.storeName();
        if (BankTransferService.isMaskedRecipient(recipient)) {
            var draft = bankTransferService.createMaskedDraft(
                    recipient, purpose, amount, transferredAt);
            String missing = amount == null || transferredAt == null
                    ? "；另外交易日期或實際金額也不完整，補名後仍會繼續詢問"
                    : "";
            return new ReceiptResult("BANK_TRANSFER_RECIPIENT_NEEDED",
                    "這是%s消費紀錄，但銀行顯示的收款人「%s」含隱碼，我不會猜公司全名。"
                            .formatted(purpose, recipient == null ? "未顯示" : recipient)
                            + "請提供完整收款公司名稱" + missing + "。\n"
                            + (draft == null ? "" : draft.retentionNotice()), 0);
        }
        if (amount == null || transferredAt == null) {
            return new ReceiptResult("BANK_TRANSFER_NEEDS_DETAILS",
                    "已辨識為轉帳紀錄，但實際交易日期或轉帳金額不完整，因此尚未建立消費紀錄。", 0);
        }
        bankTransferService.recordComplete(recipient, purpose, amount, transferredAt);
        return new ReceiptResult("BANK_TRANSFER_IMPORTED",
                "已建立%s消費紀錄：收款人 %s，NT$ %,d，日期 %s。"
                        .formatted(purpose, recipient, amount, transferredAt), 1);
    }

    private ReceiptResult handleSchoolMenu(ReceiptCommand command) {
        if (schoolMealService == null) {
            return new ReceiptResult("SCHOOL_MENU_UNAVAILABLE",
                    "學校菜單功能目前未啟用；原始圖片仍已保存。", 0);
        }
        int saved = schoolMealService.importMenu(command.documentTitle(), command.menuEntries());
        if (saved == 0) {
            return new ReceiptResult("SCHOOL_MENU_NOT_READABLE",
                    "這張圖片像學校菜單，但讀不到完整日期、餐別與餐點；原圖已保存。", 0);
        }
        return new ReceiptResult("SCHOOL_MENU_IMPORTED",
                "已保存 %d 筆學校餐點，可查詢今天早餐或本週含特定品項的日期。".formatted(saved), saved);
    }

    private ReceiptResult handleBloodDonation(ReceiptCommand command) {
        if (bloodDonationService == null) {
            return new ReceiptResult("BLOOD_DONATION_UNAVAILABLE",
                    "捐血紀錄功能目前未啟用；原始圖片仍已保存。", 0);
        }
        ReceiptCommand.BloodDonationInfo info = command.bloodDonationInfo();
        if (info == null || info.donationDate() == null) {
            return new ReceiptResult("BLOOD_DONATION_NOT_READABLE",
                    "這張圖片像捐血紀錄，但讀不到可核對的捐血日期；請補上日期。", 0);
        }
        try {
            LocalDate donationDate = LocalDate.parse(info.donationDate());
            LocalDate eligibleDate = info.nextEligibleDate() == null
                    ? null : LocalDate.parse(info.nextEligibleDate());
            bloodDonationService.record(donationDate, info.donationLocation(), eligibleDate,
                    SourceType.IMAGE);
            String eligibility = eligibleDate == null
                    ? "圖片沒有可核對的下次最早捐血日，請告訴我該日期；我不會自行推算。"
                    : "已一併保存圖片明確顯示的下次最早捐血日：" + eligibleDate + "。";
            return new ReceiptResult("BLOOD_DONATION_IMPORTED",
                    "已保存 %s 的捐血紀錄。\n%s\n若你要安排下一次捐血，請再提供日期與時間，我確認後才會建立行程。"
                            .formatted(donationDate, eligibility), 1);
        } catch (java.time.DateTimeException | IllegalArgumentException e) {
            return new ReceiptResult("BLOOD_DONATION_NOT_READABLE",
                    "捐血日期或下次最早捐血日不合理，因此沒有建立紀錄；請用文字提供正確日期。", 0);
        }
    }

    private ReceiptResult handlePaintProduct(ReceiptCommand command) {
        if (productExperienceService == null) {
            return new ReceiptResult("PAINT_PRODUCT_UNAVAILABLE",
                    "油漆商品紀錄目前未啟用；原始圖片仍已保存。", 0);
        }
        try {
            var draft = productExperienceService.capture(
                    command.paintProductInfo(), command.documentTitle());
            return new ReceiptResult("PAINT_PRODUCT_CLARIFICATION",
                    ("已辨識商品「%s」。\n\n").formatted(draft.displayName())
                            + "接下來直接告訴我你想怎麼記就好，例如：\n"
                            + "-「這桶用在客廳牆面」\n"
                            + "-「這是師傅推薦的大麥白」\n"
                            + "-「加上客廳工程、第二批色差標籤」\n"
                            + "如果希望下次購買時提醒你，也可以一起說；沒有明講，我不會自行設定。\n\n"
                            + "我也會補上這類商品常見的通用分類，之後可用品牌、商品名稱，"
                            + "或像「油漆、塗料、居家修繕」這類關鍵字查找。\n\n"
                            + java.util.Objects.toString(
                                    productExperienceService.retentionDisclosure(draft), ""),
                    0);
        } catch (IllegalArgumentException e) {
            return new ReceiptResult("PAINT_PRODUCT_NOT_READABLE",
                    "這張圖片像油漆產品，但品牌、產品或顏色都無法可靠辨識；原圖已保存。", 0);
        }
    }

    private ReceiptResult handlePaymentNotice(ReceiptCommand command) {
        if (paymentNoticeService == null) {
            return new ReceiptResult("PAYMENT_NOTICE_UNAVAILABLE",
                    "繳費通知功能目前未啟用；原始圖片仍已保存。", 0);
        }
        ReceiptCommand.PaymentNoticeInfo info = command.paymentNoticeInfo();
        if (info == null || info.dueDate() == null || info.dueDate().isBlank()) {
            return new ReceiptResult("PAYMENT_NOTICE_DUE_DATE_NEEDED",
                    "這張圖片像繳費通知，但讀不到明確繳費期限；原圖已保存，請用文字補上期限。", 0);
        }
        try {
            LocalDate dueDate = LocalDate.parse(info.dueDate());
            String title = info.title() == null || info.title().isBlank()
                    ? command.documentTitle() : info.title();
            var captured = paymentNoticeService.capture(
                    title, info.issuer(), info.amountTwd(), dueDate);
            String amount = captured.amountTwd() == null
                    ? "圖片沒有可核對的應繳金額，我不會猜。"
                    : "應繳金額：NT$ %,d。".formatted(captured.amountTwd());
            return new ReceiptResult("PAYMENT_NOTICE_CAPTURED",
                    "已保存繳費通知「%s」，期限 %s。%s\n"
                            .formatted(captured.title(), captured.dueDate(), amount)
                            + "這不代表已付款，也不會自動付款。要在期限前幾天提醒你？例如「到期前三天提醒我」。",
                    0);
        } catch (java.time.DateTimeException | IllegalArgumentException invalid) {
            return new ReceiptResult("PAYMENT_NOTICE_NOT_READABLE",
                    "繳費期限或金額不合理，因此沒有建立提醒；請用文字提供正確期限。", 0);
        }
    }

    private ReceiptResult handleUtilityBillHistory(ReceiptCommand command) {
        if (utilityBillService == null) {
            return new ReceiptResult("UTILITY_BILL_HISTORY_UNAVAILABLE",
                    "電費歷程功能目前未啟用；原始圖片仍已保存。", 0);
        }
        try {
            UtilityBillService.CaptureResult captured =
                    utilityBillService.capture(command.utilityBillInfo());
            return new ReceiptResult("UTILITY_BILL_LOCATION_NEEDED",
                    "已辨識 %d 筆電費歷程：\n%s\n這是哪個用電地點？"
                            .formatted(captured.savedRows(), captured.preview())
                            + "請回答例如「這是家裡的電費」；確認前不會併入正式歷程。",
                    0);
        } catch (IllegalArgumentException unreadable) {
            return new ReceiptResult("UTILITY_BILL_HISTORY_NOT_READABLE",
                    "這張圖片像電費歷程，但讀不到完整月份、用電量或金額；原圖已保存，"
                            + "我不會猜被截掉的數字。", 0);
        }
    }

    private java.util.Optional<LocalDate> parseDocumentDate(String value) {
        if (value == null || value.isBlank()) return java.util.Optional.empty();
        try {
            if (value.matches("\\d{1,2}-\\d{1,2}")) {
                String[] parts = value.split("-");
                int year = LocalDate.now(clock.withZone(TAIPEI)).getYear();
                return java.util.Optional.of(LocalDate.of(
                        year, Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
            }
            return java.util.Optional.of(LocalDate.parse(value));
        } catch (java.time.DateTimeException | NumberFormatException invalidDate) {
            return java.util.Optional.empty();
        }
    }

    /** 收據日期解析失敗(缺漏、格式爛)→ 當作今天(台北時間),寧可粗略不可丟棄。 */
    private static boolean containsPromptInjection(ReceiptCommand command) {
        List<String> values = new ArrayList<>();
        values.add(command.storeName());
        values.add(command.documentTitle());
        if (command.items() != null) {
            command.items().stream().filter(java.util.Objects::nonNull)
                    .map(ReceiptCommand.Line::name).forEach(values::add);
        }
        if (command.itineraryEntries() != null) {
            for (ReceiptCommand.ItineraryEntry entry : command.itineraryEntries()) {
                if (entry == null) {
                    continue;
                }
                values.add(entry.title());
                values.add(entry.placeName());
                values.add(entry.details());
            }
        }
        if (command.activities() != null) {
            values.addAll(command.activities());
        }
        if (command.notices() != null) {
            values.addAll(command.notices());
        }
        if (command.contactCard() != null) {
            values.add(command.contactCard().displayName());
            values.add(command.contactCard().organizationName());
            values.add(command.contactCard().profession());
            values.add(command.contactCard().address());
            if (command.contactCard().phoneNumbers() != null) {
                values.addAll(command.contactCard().phoneNumbers());
            }
            if (command.contactCard().emails() != null) {
                values.addAll(command.contactCard().emails());
            }
        }
        if (command.paymentNoticeInfo() != null) {
            values.add(command.paymentNoticeInfo().title());
            values.add(command.paymentNoticeInfo().issuer());
            values.add(command.paymentNoticeInfo().dueDate());
        }
        if (command.utilityBillInfo() != null) {
            values.add(command.utilityBillInfo().provider());
            if (command.utilityBillInfo().entries() != null) {
                command.utilityBillInfo().entries().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(ReceiptCommand.UtilityBillEntry::billingMonth)
                        .forEach(values::add);
            }
        }
        if (command.bloodDonationInfo() != null) {
            values.add(command.bloodDonationInfo().donationDate());
            values.add(command.bloodDonationInfo().donationLocation());
            values.add(command.bloodDonationInfo().nextEligibleDate());
        }
        if (command.paintProductInfo() != null) {
            values.add(command.paintProductInfo().productName());
            values.add(command.paintProductInfo().brandName());
            values.add(command.paintProductInfo().colorName());
            values.add(command.paintProductInfo().colorCode());
        }
        return PromptInjectionGuard.inspectExternalContent(values).suspicious();
    }

    private LocalDate parseDateOrToday(String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now(clock.withZone(TAIPEI));
        }
        try {
            return LocalDate.parse(date);
        } catch (Exception e) {
            return LocalDate.now(clock.withZone(TAIPEI));
        }
    }

    /** 收據處理結果:回覆訊息 + 入庫行數。 */
    public record ReceiptResult(String action, String message, int savedCount) {
        public ReceiptResult(String message, int savedCount) {
            this("RECEIPT_IMPORTED", message, savedCount);
        }

        public ReceiptResult {
            String icon = action != null && action.startsWith("TRAVEL_ITINERARY") ? "🗺️"
                    : action != null && action.startsWith("EVENT_POSTER") ? "🎟️"
                    : action != null && action.startsWith("EVENT_REGISTRATION") ? "✅"
                    : action != null && action.startsWith("MEDICAL_APPOINTMENT") ? "🏥"
                    : action != null && action.startsWith("WORK_SCHOOL_SUSPENSION") ? "🌀" : "🧾";
            if (action != null && action.startsWith("BUSINESS_CARD")) icon = "📇";
            if (action != null && action.startsWith("TAX_PAYMENT")) icon = "🏛️";
            if (action != null && action.startsWith("BANK_TRANSFER")) icon = "🏦";
            if (action != null && action.startsWith("SCHOOL_MENU")) icon = "🍱";
            if (action != null && action.startsWith("BLOOD_DONATION")) icon = "🩸";
            if (action != null && action.startsWith("PAINT_PRODUCT")) icon = "🎨";
            if (action != null && action.startsWith("UTILITY_BILL")) icon = "⚡";
            message = IntentReplyFormatter.format(icon, message);
        }
    }
}
