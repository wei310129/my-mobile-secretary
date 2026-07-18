package com.aproject.aidriven.mymobilesecretary.integration.calendar;

import com.aproject.aidriven.mymobilesecretary.integration.weather.CwaProperties;
import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 以中央氣象署 A-A0087-001 國農曆對照 CSV 提供可追溯的日期換算。 */
@Component
public class CwaLunarCalendarProvider implements LunarCalendarConversionProvider {

    static final String DATASET = "A-A0087-001";
    static final String SOURCE = "中央氣象署 A-A0087-001 國農曆對照";

    private final RestClient restClient;
    private final CwaProperties properties;

    public CwaLunarCalendarProvider(RestClient.Builder builder, CwaProperties properties) {
        this.properties = properties;
        URI configured = URI.create(properties.baseUrl());
        String origin = configured.getScheme() + "://" + configured.getAuthority();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.timeout().toMillis());
        factory.setReadTimeout((int) properties.timeout().toMillis());
        this.restClient = builder.baseUrl(origin).requestFactory(factory).build();
    }

    @Override
    @Cacheable(cacheNames = "lunarCalendar", key = "#lunarDate")
    public Conversion convert(LunarDate lunarDate) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            return Conversion.failed(Status.TEMPORARILY_UNAVAILABLE,
                    SOURCE + "（尚未設定 CWA 授權碼）");
        }
        String csv;
        try {
            csv = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/fileapi/v1/opendataapi/" + DATASET)
                            .queryParam("Authorization", properties.apiKey())
                            .queryParam("format", "CSV")
                            .build())
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException exception) {
            return Conversion.failed(Status.TEMPORARILY_UNAVAILABLE,
                    SOURCE + "（下載失敗）");
        }
        if (csv == null || csv.isBlank()) {
            return Conversion.failed(Status.TEMPORARILY_UNAVAILABLE,
                    SOURCE + "（空白回應）");
        }
        try {
            return find(csv, lunarDate);
        } catch (RuntimeException exception) {
            return Conversion.failed(Status.TEMPORARILY_UNAVAILABLE,
                    SOURCE + "（CSV 格式無法辨識）");
        }
    }

    static Conversion find(String csv, LunarDate requested) {
        List<List<String>> rows = parseCsv(csv);
        if (rows.size() < 2) {
            return Conversion.failed(Status.TEMPORARILY_UNAVAILABLE, SOURCE + "（沒有資料列）");
        }
        Map<String, Integer> columns = columns(rows.getFirst());
        int gregorian = required(columns, "GregorianCalendar");
        int lunarYear = required(columns, "AgriculturalCalendar");
        int lunarMonth = required(columns, "AgriculturalCalendarMonth");
        int lunarDay = required(columns, "AgriculturalCalendarDay");
        String expectedYear = cyclicalYear(requested.year());
        boolean yearPresent = false;
        List<Candidate> candidates = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() <= Math.max(Math.max(gregorian, lunarYear), Math.max(lunarMonth, lunarDay))) {
                continue;
            }
            if (!row.get(lunarYear).replace("年", "").strip().contains(expectedYear)) continue;
            yearPresent = true;
            String monthValue = row.get(lunarMonth).strip();
            LeapMonth leap = monthValue.startsWith("閏") ? LeapMonth.LEAP : LeapMonth.REGULAR;
            int month = Integer.parseInt(monthValue.replace("閏", "").strip());
            int day = Integer.parseInt(row.get(lunarDay).strip());
            if (month != requested.month() || day != requested.day()) continue;
            if (requested.leapMonth() != LeapMonth.UNSPECIFIED
                    && requested.leapMonth() != leap) continue;
            candidates.add(new Candidate(LocalDate.parse(row.get(gregorian).strip()), leap));
        }
        if (candidates.size() > 1 && requested.leapMonth() == LeapMonth.UNSPECIFIED) {
            return Conversion.ambiguous(candidates, SOURCE);
        }
        if (candidates.size() == 1) {
            Candidate candidate = candidates.getFirst();
            return Conversion.resolved(candidate.gregorianDate(), candidate.leapMonth(), SOURCE);
        }
        return Conversion.failed(yearPresent ? Status.INVALID_DATE : Status.OUT_OF_RANGE, SOURCE);
    }

    static String cyclicalYear(int gregorianLunarYear) {
        String stems = "甲乙丙丁戊己庚辛壬癸";
        String branches = "子丑寅卯辰巳午未申酉戌亥";
        int offset = gregorianLunarYear - 4;
        return "" + stems.charAt(Math.floorMod(offset, 10))
                + branches.charAt(Math.floorMod(offset, 12));
    }

    private static int required(Map<String, Integer> columns, String name) {
        Integer value = columns.get(name.toLowerCase(java.util.Locale.ROOT));
        if (value == null) throw new IllegalArgumentException("missing CSV column: " + name);
        return value;
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> values = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            values.put(header.get(i).replace("\ufeff", "").strip()
                    .toLowerCase(java.util.Locale.ROOT), i);
        }
        return values;
    }

    /** 足以處理官方 CSV 的引號、逗號與 CRLF，不以 split 破壞 quoted 欄位。 */
    private static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char ch = csv.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                row.add(field.toString());
                field.setLength(0);
            } else if ((ch == '\n' || ch == '\r') && !quoted) {
                if (ch == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                if (row.stream().anyMatch(value -> !value.isBlank())) rows.add(List.copyOf(row));
                row.clear();
            } else {
                field.append(ch);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            if (row.stream().anyMatch(value -> !value.isBlank())) rows.add(List.copyOf(row));
        }
        return List.copyOf(rows);
    }
}
