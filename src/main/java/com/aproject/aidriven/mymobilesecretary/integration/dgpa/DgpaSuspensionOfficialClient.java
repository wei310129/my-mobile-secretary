package com.aproject.aidriven.mymobilesecretary.integration.dgpa;

import com.aproject.aidriven.mymobilesecretary.safety.application.SuspensionOfficialClient;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Verifies against the Executive Yuan DGPA work/school suspension page. */
@Component
public class DgpaSuspensionOfficialClient implements SuspensionOfficialClient {
    private static final Pattern HTML = Pattern.compile("<[^>]+>");
    private final RestClient restClient;
    private final String officialUrl;

    public DgpaSuspensionOfficialClient(RestClient.Builder builder,
            @Value("${app.suspension.official-url:https://www.dgpa.gov.tw/typh/daily/nds.html}")
            String officialUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restClient = builder.requestFactory(factory).build();
        this.officialUrl = officialUrl;
    }

    @Override
    public Verification verify(LocalDate noticeDate, String extractedSummary) {
        try {
            String html = restClient.get().uri(officialUrl).retrieve().body(String.class);
            if (html == null || html.isBlank()) {
                return new Verification(false, false, "官方頁面沒有回傳內容。", officialUrl);
            }
            String page = normalize(HTML.matcher(html).replaceAll(" "));
            String dateToken = "%d年%d月%d日".formatted(
                    noticeDate.getYear() - 1911, noticeDate.getMonthValue(), noticeDate.getDayOfMonth());
            boolean dateMatches = page.contains(dateToken);
            boolean statusMatches = containsStatus(page, extractedSummary);
            if (dateMatches && statusMatches) {
                return new Verification(true, true,
                        "行政院人事行政總處頁面可找到同日期及停班停課內容。", officialUrl);
            }
            return new Verification(true, false,
                    "已查行政院人事行政總處目前公告頁，但未找到可同時核對日期與縣市狀態的內容；圖片資訊仍視為未獲官方頁面證實。",
                    officialUrl);
        } catch (RuntimeException failure) {
            return new Verification(false, false,
                    "行政院人事行政總處頁面目前無法連線，沒有把圖片內容當成官方事實。", officialUrl);
        }
    }

    private static boolean containsStatus(String officialPage, String extractedSummary) {
        if (!officialPage.contains("停止上班") && !officialPage.contains("停止上課")) return false;
        return Arrays.stream(extractedSummary.split("[\\n：:]"))
                .map(String::strip)
                .filter(value -> value.endsWith("市") || value.endsWith("縣"))
                .anyMatch(officialPage::contains);
    }

    private static String normalize(String value) {
        return value.replace("&nbsp;", " ").replaceAll("\\s+", "").strip();
    }
}
