package com.aproject.aidriven.mymobilesecretary.utility.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptCommand;
import com.aproject.aidriven.mymobilesecretary.utility.domain.UtilityBillRecord;
import com.aproject.aidriven.mymobilesecretary.utility.domain.UtilityBillRecord.Status;
import com.aproject.aidriven.mymobilesecretary.utility.persistence.UtilityBillRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic import, location confirmation, and query flow for utility-bill history. */
@Service
@Transactional
public class UtilityBillService {

    private static final int MAX_IMAGE_ROWS = 36;
    private static final Pattern ROC_MONTH = Pattern.compile("(?:民國)?(\\d{2,3})[年/.-](\\d{1,2})月?");
    private static final Pattern ISO_MONTH = Pattern.compile("(20\\d{2})[-/.](\\d{1,2})");
    private static final Pattern WESTERN_MONTH = Pattern.compile("(20\\d{2})年(\\d{1,2})月?");
    private static final Pattern MONTH_ONLY = Pattern.compile("(\\d{1,2})月");
    private static final Pattern YEAR = Pattern.compile("(?:民國)?(\\d{2,3})年|((?:19|20)\\d{2})年");
    private static final Pattern YEAR_PAIR = Pattern.compile(
            "(?<!\\d)(\\d{2,4})\\s*[、,，及和與/]\\s*(\\d{2,4})\\s*(?:兩年|年)");
    private static final Pattern NEGATED_YEAR = Pattern.compile(
            "(?:根本)?(?:沒有|沒)提到\\s*(?:民國)?\\d{2,4}年");
    private static final Pattern THRESHOLD_BEFORE = Pattern.compile(
            "(超過|高於|大於|逾|至少|不低於)\\s*(?:NT\\$|新台幣)?\\s*([\\d,]+)");
    private static final Pattern THRESHOLD_AFTER = Pattern.compile(
            "(?:NT\\$|新台幣)?\\s*([\\d,]+)\\s*(以上)");
    private static final Pattern LOCATION = Pattern.compile(
            "(?:這是|地點(?:是|叫)|用電地點(?:是|叫))\\s*([^，。,.]{1,30}?)(?:的)?電費(?:帳單|紀錄|歷程)?(?:[，。,.]|$)");

    private final UtilityBillRecordRepository repository;
    private final Clock clock;

    public UtilityBillService(UtilityBillRecordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public CaptureResult capture(ReceiptCommand.UtilityBillInfo info) {
        if (info == null || info.entries() == null) {
            throw new IllegalArgumentException("utility bill history is empty");
        }
        UUID actor = WorkspaceContextHolder.requireContext().actorId();
        Instant now = Instant.now(clock);
        repository.findByCreatedByUserIdAndStatusOrderByCreatedAtDesc(actor, Status.PENDING)
                .forEach(row -> row.supersede(now));

        UUID batchId = UUID.randomUUID();
        List<UtilityBillRecord> pending = new ArrayList<>();
        for (ReceiptCommand.UtilityBillEntry entry : info.entries().stream()
                .filter(java.util.Objects::nonNull).limit(MAX_IMAGE_ROWS).toList()) {
            YearMonth month = parseBillingMonth(entry.billingMonth()).orElse(null);
            Integer usage = nonNegative(entry.usageKwh());
            Integer amount = validatedAmount(entry.amountTwd(), usage);
            if (month == null || (usage == null && amount == null)) continue;
            pending.add(UtilityBillRecord.pending(batchId, info.provider(), month.atDay(1),
                    usage, amount, now));
        }
        if (pending.isEmpty()) {
            throw new IllegalArgumentException("no readable utility bill rows");
        }
        repository.saveAll(pending);
        return new CaptureResult(batchId, pending.size(), preview(pending));
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        if (text == null || !text.contains("電費")) return Optional.empty();
        UUID actor = WorkspaceContextHolder.requireContext().actorId();
        List<UtilityBillRecord> pending = repository
                .findByCreatedByUserIdAndStatusOrderByCreatedAtDesc(actor, Status.PENDING);
        if (!pending.isEmpty()) {
            String location = extractLocation(text);
            if (location != null) {
                beforeMutation.run();
                int imported = confirmLatestBatch(actor, pending.getFirst().getImportBatchId(), location);
                return Optional.of(IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                        "已把 %d 筆電費歷程歸到「%s」。之後可以問我上個月、指定年月或某年度各月份與合計電費。"
                                .formatted(imported, location)));
            }
            return Optional.of(IntentResult.clarificationNeeded(
                    "我已暫存這張電費歷程，但還不能判斷是哪個用電地點。請告訴我，例如「這是家裡的電費」或「用電地點是辦公室」。"));
        }

        if (looksLikeHistoryClarification(text)) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "我知道這是電費帳單歷程。這是哪個用電地點？確認地點後我才會保存各月份用電量與金額；"
                            + "目前這則對話沒有可確認的待匯入資料，請重新傳一次原圖，我不會猜被截掉的數字。"));
        }
        if (!looksLikeQuery(text)) return Optional.empty();
        return Optional.of(query(actor, text));
    }

    private int confirmLatestBatch(UUID actor, UUID batchId, String location) {
        List<UtilityBillRecord> rows = repository
                .findByCreatedByUserIdAndImportBatchIdAndStatusOrderByBillingMonthDesc(
                        actor, batchId, Status.PENDING);
        Instant now = Instant.now(clock);
        int imported = 0;
        for (UtilityBillRecord row : rows) {
            Optional<UtilityBillRecord> existing = repository
                    .findFirstByCreatedByUserIdAndStatusAndProviderAndLocationLabelAndBillingMonth(
                            actor, Status.CONFIRMED, row.getProvider(), location,
                            row.getBillingMonth());
            if (existing.isPresent()) {
                existing.get().replaceMeasurements(row.getUsageKwh(), row.getAmountTwd(), now);
                row.supersede(now);
            } else {
                row.confirm(location, now);
            }
            imported++;
        }
        return imported;
    }

    private IntentResult query(UUID actor, String text) {
        List<UtilityBillRecord> allRows = repository
                .findByCreatedByUserIdAndStatusOrderByBillingMonthAsc(actor, Status.CONFIRMED);
        if (allRows.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                    "目前沒有已確認的電費歷程。");
        }
        LocationSelection selection = selectLocation(text, allRows);
        if (selection.clarification() != null) return selection.clarification();
        String location = selection.location();
        List<UtilityBillRecord> locationRows = allRows.stream()
                .filter(row -> location.equals(row.getLocationLabel())).toList();

        AmountThreshold threshold = amountThreshold(text);
        if (threshold != null) return thresholdResult(location, locationRows, threshold);

        Integer historicalMonth = historicalMonth(text);
        if (historicalMonth != null) {
            List<UtilityBillRecord> rows = locationRows.stream()
                    .filter(row -> row.getBillingMonth().getMonthValue() == historicalMonth).toList();
            return historyRowsResult(location, "%d 月的歷年".formatted(historicalMonth), rows);
        }

        if (looksLikeLatestQuery(text)) {
            UtilityBillRecord latest = locationRows.getLast();
            return monthlyResult(location, YearMonth.from(latest.getBillingMonth()), latest);
        }
        if (looksLikeMaximumQuery(text)) {
            return locationRows.stream().filter(row -> row.getAmountTwd() != null)
                    .max(Comparator.comparingInt(UtilityBillRecord::getAmountTwd))
                    .map(row -> IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                            "%s目前已記錄最高的電費是 %s，NT$ %,d%s。".formatted(
                                    location, displayMonth(YearMonth.from(row.getBillingMonth())),
                                    row.getAmountTwd(), usageSuffix(row))))
                    .orElseGet(() -> IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                            "%s目前沒有可比較金額的電費紀錄。".formatted(location)));
        }
        if (text.contains("平均")) {
            List<Integer> amounts = locationRows.stream().map(UtilityBillRecord::getAmountTwd)
                    .filter(java.util.Objects::nonNull).toList();
            if (amounts.isEmpty()) {
                return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                        "%s目前沒有可計算平均值的電費紀錄。".formatted(location));
            }
            double average = amounts.stream().mapToInt(Integer::intValue).average().orElseThrow();
            return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                    "%s目前 %d 筆已知金額的平均電費是 NT$ %,.0f。".formatted(
                            location, amounts.size(), average));
        }

        List<Integer> years = targetYears(text);
        if (looksLikeComparison(text) && years.size() < 2
                && text.contains("今年") && text.contains("去年")) {
            int currentYear = LocalDate.now(clock).getYear();
            years = List.of(currentYear, currentYear - 1);
        }
        if (looksLikeComparison(text) && years.size() >= 2) {
            return comparisonResult(location, locationRows, years.get(0), years.get(1));
        }
        if (years.size() >= 2) {
            return multiYearResult(location, locationRows, years);
        }

        YearMonth target = targetMonth(text);
        Integer year = years.isEmpty() ? targetYear(text) : years.getFirst();
        boolean annual = text.contains("各月") || text.contains("全年")
                || text.contains("年度") || text.contains("總和") || text.contains("合計")
                || text.contains("總共") || (year != null && target == null);
        if (isHistoryListQuery(text) && !annual && target == null) {
            return historyRowsResult(location, "全部", locationRows);
        }
        if (!annual && target == null) {
            return IntentResult.clarificationNeeded("請指定要查哪個月份或年度，例如「上個月電費」或「113 年各月份電費」。");
        }
        int queryYear = annual ? (year == null ? LocalDate.now(clock).getYear() : year)
                : target.getYear();
        List<UtilityBillRecord> rows = locationRows.stream()
                .filter(row -> row.getBillingMonth().getYear() == queryYear)
                .filter(row -> annual || row.getBillingMonth().equals(target.atDay(1)))
                .toList();
        if (rows.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                    "目前沒有 %s 的電費歷程。".formatted(annual ? displayYear(queryYear) : displayMonth(target)));
        }
        if (!annual) return monthlyResult(location, target, rows.getFirst());

        List<String> details = rows.stream().map(row -> {
            String amount = row.getAmountTwd() == null ? "金額未顯示"
                    : "NT$ %,d".formatted(row.getAmountTwd());
            String usage = row.getUsageKwh() == null ? "" : "，%,d kWh".formatted(row.getUsageKwh());
            return "%02d 月：%s%s".formatted(row.getBillingMonth().getMonthValue(), amount, usage);
        }).toList();
        int total = rows.stream().map(UtilityBillRecord::getAmountTwd)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
        long missing = rows.stream().filter(row -> row.getAmountTwd() == null).count();
        String totalText = missing == 0
                ? "已記錄 %d 個月份，合計 NT$ %,d".formatted(rows.size(), total)
                : "已知金額小計 NT$ %,d；另有 %d 個月份金額未顯示，不能當作完整年度總額"
                        .formatted(total, missing);
        return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                "%s的 %s 電費：\n%s\n%s。".formatted(location, displayYear(queryYear),
                        String.join("\n", details), totalText));
    }

    private static LocationSelection selectLocation(String text, List<UtilityBillRecord> rows) {
        LinkedHashSet<String> locations = new LinkedHashSet<>();
        rows.forEach(row -> locations.add(row.getLocationLabel()));
        String selected = locations.stream().filter(location -> locationMentioned(text, location))
                .findFirst().orElse(null);
        if (selected == null && locations.size() > 1) {
            return new LocationSelection(null, IntentResult.clarificationNeeded(
                    "找到多個用電地點：%s。請指定要查哪一個。".formatted(String.join("、", locations))));
        }
        return new LocationSelection(selected == null ? locations.iterator().next() : selected, null);
    }

    private static boolean locationMentioned(String text, String location) {
        if (text.contains(location)) return true;
        boolean asksHome = text.contains("我家") || text.contains("家裡") || text.contains("家中");
        boolean locationIsHome = location.contains("我家") || location.contains("家裡") || location.contains("家中");
        return asksHome && locationIsHome;
    }

    private static IntentResult thresholdResult(String location, List<UtilityBillRecord> rows,
                                                AmountThreshold threshold) {
        List<UtilityBillRecord> matches = rows.stream().filter(row -> row.getAmountTwd() != null)
                .filter(row -> threshold.inclusive()
                        ? row.getAmountTwd() >= threshold.amount()
                        : row.getAmountTwd() > threshold.amount())
                .toList();
        if (matches.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                    "%s目前已確認的電費紀錄中，沒有%s NT$ %,d 的月份。".formatted(
                            location, threshold.inclusive() ? "達到" : "超過", threshold.amount()));
        }
        return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                "%s有 %d 個月份電費%s NT$ %,d：\n%s".formatted(
                        location, matches.size(), threshold.inclusive() ? "達到" : "超過",
                        threshold.amount(), String.join("\n", historyDetails(matches))));
    }

    private static IntentResult historyRowsResult(String location, String scope,
                                                  List<UtilityBillRecord> rows) {
        if (rows.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                    "%s目前沒有%s電費紀錄。".formatted(location, scope));
        }
        return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                "%s%s電費紀錄：\n%s".formatted(location, scope, String.join("\n", historyDetails(rows))));
    }

    private static List<String> historyDetails(List<UtilityBillRecord> rows) {
        return rows.stream().map(row -> {
            String amount = row.getAmountTwd() == null ? "金額未顯示"
                    : "NT$ %,d".formatted(row.getAmountTwd());
            return "%s：%s%s".formatted(displayMonth(YearMonth.from(row.getBillingMonth())),
                    amount, usageSuffix(row));
        }).toList();
    }

    private static String usageSuffix(UtilityBillRecord row) {
        return row.getUsageKwh() == null ? "" : "，%,d kWh".formatted(row.getUsageKwh());
    }

    private static IntentResult comparisonResult(String location, List<UtilityBillRecord> rows,
                                                 int firstYear, int secondYear) {
        List<UtilityBillRecord> first = rows.stream()
                .filter(row -> row.getBillingMonth().getYear() == firstYear).toList();
        List<UtilityBillRecord> second = rows.stream()
                .filter(row -> row.getBillingMonth().getYear() == secondYear).toList();
        if (first.isEmpty() || second.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                    "%s缺少 %s 或 %s 的電費紀錄，目前無法比較。".formatted(
                            location, displayYear(firstYear), displayYear(secondYear)));
        }
        int firstTotal = knownAmountTotal(first);
        int secondTotal = knownAmountTotal(second);
        int difference = firstTotal - secondTotal;
        return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                ("%s已記錄電費比較：\n- %s：%d 個月份，已知金額 NT$ %,d%s\n"
                        + "- %s：%d 個月份，已知金額 NT$ %,d%s\n- 差額：%s。").formatted(
                                location, displayYear(firstYear), first.size(), firstTotal,
                                missingAmountNote(first), displayYear(secondYear), second.size(),
                                secondTotal, missingAmountNote(second), signedAmount(difference)));
    }

    private static IntentResult multiYearResult(String location, List<UtilityBillRecord> rows,
                                                List<Integer> years) {
        List<String> sections = new ArrayList<>();
        for (int year : years) {
            List<UtilityBillRecord> annualRows = rows.stream()
                    .filter(row -> row.getBillingMonth().getYear() == year).toList();
            if (annualRows.isEmpty()) {
                sections.add("%s：目前無紀錄".formatted(displayYear(year)));
                continue;
            }
            int total = knownAmountTotal(annualRows);
            String summary = annualRows.stream().map(row -> "%02d 月：%s%s".formatted(
                    row.getBillingMonth().getMonthValue(),
                    row.getAmountTwd() == null ? "金額未顯示"
                            : "NT$ %,d".formatted(row.getAmountTwd()),
                    usageSuffix(row))).collect(java.util.stream.Collectors.joining("\n"));
            long missing = annualRows.stream().filter(row -> row.getAmountTwd() == null).count();
            String totalText = missing == 0 ? "已知金額合計 NT$ %,d".formatted(total)
                    : "已知金額小計 NT$ %,d；另有 %d 個月份金額未顯示"
                            .formatted(total, missing);
            sections.add("%s：\n%s\n%s".formatted(displayYear(year), summary, totalText));
        }
        return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                "%s的多年度電費明細：\n%s。".formatted(location, String.join("\n\n", sections)));
    }

    private static int knownAmountTotal(List<UtilityBillRecord> rows) {
        return rows.stream().map(UtilityBillRecord::getAmountTwd).filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue).sum();
    }

    private static String missingAmountNote(List<UtilityBillRecord> rows) {
        long missing = rows.stream().filter(row -> row.getAmountTwd() == null).count();
        return missing == 0 ? "" : "（另有 %d 筆金額未顯示）".formatted(missing);
    }

    private static String signedAmount(int difference) {
        if (difference == 0) return "相同";
        return difference > 0 ? "前者多 NT$ %,d".formatted(difference)
                : "前者少 NT$ %,d".formatted(Math.abs(difference));
    }

    private static IntentResult monthlyResult(String location, YearMonth month,
                                              UtilityBillRecord row) {
        String amount = row.getAmountTwd() == null ? "圖片沒有可核對的金額"
                : "NT$ %,d".formatted(row.getAmountTwd());
        String usage = row.getUsageKwh() == null ? "" : "；用電量 %,d kWh".formatted(row.getUsageKwh());
        return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                "%s %s 的電費是 %s%s。".formatted(location, displayMonth(month), amount, usage));
    }

    private static AmountThreshold amountThreshold(String text) {
        Matcher before = THRESHOLD_BEFORE.matcher(text);
        if (before.find()) {
            Integer amount = parseAmount(before.group(2));
            if (amount == null) return null;
            boolean inclusive = before.group(1).equals("至少") || before.group(1).equals("不低於");
            return new AmountThreshold(amount, inclusive);
        }
        Matcher after = THRESHOLD_AFTER.matcher(text);
        if (after.find()) {
            Integer amount = parseAmount(after.group(1));
            return amount == null ? null : new AmountThreshold(amount, true);
        }
        return null;
    }

    private static Integer parseAmount(String raw) {
        try {
            return Integer.valueOf(raw.replace(",", ""));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Integer historicalMonth(String text) {
        if (!text.contains("歷年") && !text.contains("每年") && !text.contains("往年")) return null;
        Matcher matcher = MONTH_ONLY.matcher(text);
        if (!matcher.find()) return null;
        int month = Integer.parseInt(matcher.group(1));
        return month >= 1 && month <= 12 ? month : null;
    }

    private static boolean looksLikeLatestQuery(String text) {
        return text.contains("最近一期") || text.contains("最新一期")
                || text.contains("最新電費") || text.contains("最近一次電費")
                || text.contains("上一期電費");
    }

    private static boolean looksLikeMaximumQuery(String text) {
        return text.contains("最高電費") || text.contains("電費最高")
                || text.contains("最貴電費") || text.contains("電費最貴");
    }

    private static boolean looksLikeComparison(String text) {
        return text.contains("比較") || text.contains("差多少") || text.contains("差額");
    }

    private static boolean isHistoryListQuery(String text) {
        return text.contains("歷年電費") || text.contains("電費歷程")
                || text.contains("電費紀錄") || text.contains("全部電費")
                || text.contains("所有電費") || text.contains("以前的電費");
    }

    private static List<Integer> targetYears(String text) {
        text = NEGATED_YEAR.matcher(text).replaceAll("");
        LinkedHashSet<Integer> years = new LinkedHashSet<>();
        Matcher pair = YEAR_PAIR.matcher(text);
        while (pair.find()) {
            years.add(normalizeYear(pair.group(1)));
            years.add(normalizeYear(pair.group(2)));
        }
        Matcher matcher = YEAR.matcher(text);
        while (matcher.find()) {
            int year = matcher.group(2) == null
                    ? Integer.parseInt(matcher.group(1)) + 1911
                    : Integer.parseInt(matcher.group(2));
            years.add(year);
        }
        return List.copyOf(years);
    }

    private static int normalizeYear(String raw) {
        int year = Integer.parseInt(raw);
        return year < 1000 ? year + 1911 : year;
    }

    static Optional<YearMonth> parseBillingMonth(String raw) {
        if (raw == null) return Optional.empty();
        Matcher iso = ISO_MONTH.matcher(raw.strip());
        if (iso.find()) return validMonth(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)));
        Matcher western = WESTERN_MONTH.matcher(raw.strip());
        if (western.find()) return validMonth(Integer.parseInt(western.group(1)),
                Integer.parseInt(western.group(2)));
        Matcher roc = ROC_MONTH.matcher(raw.strip());
        if (roc.find()) return validMonth(Integer.parseInt(roc.group(1)) + 1911,
                Integer.parseInt(roc.group(2)));
        return Optional.empty();
    }

    private YearMonth targetMonth(String text) {
        LocalDate today = LocalDate.now(clock);
        if (text.contains("上個月") || text.contains("上月")) return YearMonth.from(today).minusMonths(1);
        Matcher western = WESTERN_MONTH.matcher(text);
        if (western.find()) return validMonth(Integer.parseInt(western.group(1)),
                Integer.parseInt(western.group(2))).orElse(null);
        Matcher roc = ROC_MONTH.matcher(text);
        if (roc.find()) return validMonth(Integer.parseInt(roc.group(1)) + 1911,
                Integer.parseInt(roc.group(2))).orElse(null);
        Matcher iso = ISO_MONTH.matcher(text);
        if (iso.find()) return validMonth(Integer.parseInt(iso.group(1)),
                Integer.parseInt(iso.group(2))).orElse(null);
        Matcher month = MONTH_ONLY.matcher(text);
        if (month.find()) {
            int year = text.contains("去年") ? today.getYear() - 1 : today.getYear();
            return validMonth(year, Integer.parseInt(month.group(1))).orElse(null);
        }
        return null;
    }

    private Integer targetYear(String text) {
        LocalDate today = LocalDate.now(clock);
        List<Integer> years = targetYears(text);
        if (!years.isEmpty()) return years.getFirst();
        if (text.contains("去年")) return today.getYear() - 1;
        if (text.contains("今年")) return today.getYear();
        return null;
    }

    private static Optional<YearMonth> validMonth(int year, int month) {
        try { return Optional.of(YearMonth.of(year, month)); }
        catch (java.time.DateTimeException invalid) { return Optional.empty(); }
    }

    private static Integer nonNegative(Integer value) {
        return value == null || value < 0 ? null : value;
    }

    private static Integer validatedAmount(Integer value, Integer usageKwh) {
        Integer amount = nonNegative(value);
        // The structured image parser can emit numeric zero for an absent/cropped amount.
        // Positive usage with a truly zero bill needs explicit confirmation, not silent import.
        return amount != null && amount == 0 && usageKwh != null && usageKwh > 0 ? null : amount;
    }

    private static String extractLocation(String text) {
        Matcher matcher = LOCATION.matcher(text.strip());
        if (!matcher.find()) return null;
        String location = matcher.group(1).strip();
        return location.equals("這") || location.contains("帳單") || location.contains("歷程")
                ? null : location;
    }

    private static boolean looksLikeHistoryClarification(String text) {
        boolean describesImport = text.contains("這是") || text.contains("幫我記住")
                || text.contains("你要問這是哪裡") || text.contains("這是哪裡的電費");
        return describesImport && (text.contains("帳單紀錄") || text.contains("電費歷程")
                || text.contains("電費帳單歷史"));
    }

    private static boolean looksLikeQuery(String text) {
        if (text.contains("要繳") || text.contains("提醒我") || text.contains("記得繳")) return false;
        return text.contains("多少") || text.contains("各月") || text.contains("明細")
                || text.contains("總和")
                || text.contains("合計") || text.contains("總共") || text.contains("歷程")
                || text.contains("紀錄") || text.contains("歷年") || text.contains("每年")
                || text.contains("往年") || text.contains("最近一期") || text.contains("最新")
                || text.contains("上一期") || text.contains("最高") || text.contains("最貴")
                || text.contains("平均") || text.contains("上個月") || text.contains("上月")
                || text.contains("去年") || text.contains("今年")
                || text.contains("超過") || text.contains("高於") || text.contains("大於")
                || text.contains("至少") || text.contains("不低於") || text.contains("以上")
                || YEAR.matcher(text).find() || YEAR_PAIR.matcher(text).find()
                || ROC_MONTH.matcher(text).find()
                || WESTERN_MONTH.matcher(text).find() || ISO_MONTH.matcher(text).find()
                || MONTH_ONLY.matcher(text).find();
    }

    private static String preview(List<UtilityBillRecord> rows) {
        return rows.stream().map(row -> {
            String amount = row.getAmountTwd() == null ? "金額未顯示"
                    : "NT$ %,d".formatted(row.getAmountTwd());
            String usage = row.getUsageKwh() == null ? "" : "／%,d kWh".formatted(row.getUsageKwh());
            return "%s：%s%s".formatted(displayMonth(YearMonth.from(row.getBillingMonth())), amount, usage);
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String displayMonth(YearMonth month) {
        return "民國 %d 年 %d 月".formatted(month.getYear() - 1911, month.getMonthValue());
    }

    private static String displayYear(int year) {
        return "民國 %d 年".formatted(year - 1911);
    }

    private record AmountThreshold(int amount, boolean inclusive) { }

    private record LocationSelection(String location, IntentResult clarification) { }

    public record CaptureResult(UUID batchId, int savedRows, String preview) { }
}
