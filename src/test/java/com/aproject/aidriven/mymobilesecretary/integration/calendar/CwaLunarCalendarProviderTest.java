package com.aproject.aidriven.mymobilesecretary.integration.calendar;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.integration.weather.CwaProperties;
import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.LeapMonth;
import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.LunarDate;
import com.aproject.aidriven.mymobilesecretary.intent.application.LunarCalendarConversionProvider.Status;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class CwaLunarCalendarProviderTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsOfficialCsvAndResolvesRegularMonth() {
        respond(200, csv("2026-09-25", "丙午年", "8", "15"));

        var result = provider("key").convert(new LunarDate(2026, 8, 15, LeapMonth.UNSPECIFIED));

        assertThat(result.status()).isEqualTo(Status.RESOLVED);
        assertThat(result.candidates().getFirst().gregorianDate().toString())
                .isEqualTo("2026-09-25");
        assertThat(result.sourceReference()).contains("中央氣象署", "A-A0087-001");
    }

    @Test
    void distinguishesRegularAndLeapMonthAndReturnsAmbiguity() {
        respond(200, csv("2033-08-25", "癸丑年", "7", "1")
                + "2033-09-23,122,癸丑年,閏7,1,5\n");

        var result = provider("key").convert(new LunarDate(2033, 7, 1, LeapMonth.UNSPECIFIED));

        assertThat(result.status()).isEqualTo(Status.AMBIGUOUS_LEAP_MONTH);
        assertThat(result.candidates()).extracting(candidate -> candidate.leapMonth())
                .containsExactly(LeapMonth.REGULAR, LeapMonth.LEAP);
    }

    @Test
    void noApiKeyFailsClosedWithoutCallingNetwork() {
        var result = provider("").convert(new LunarDate(2026, 8, 15, LeapMonth.UNSPECIFIED));

        assertThat(result.status()).isEqualTo(Status.TEMPORARILY_UNAVAILABLE);
        assertThat(result.sourceReference()).contains("尚未設定 CWA 授權碼");
    }

    @Test
    void malformedOrNonSuccessResponseFailsClosed() {
        respond(500, "error");

        var result = provider("key").convert(new LunarDate(2026, 8, 15, LeapMonth.UNSPECIFIED));

        assertThat(result.status()).isEqualTo(Status.TEMPORARILY_UNAVAILABLE);
        assertThat(result.sourceReference()).contains("下載失敗");
    }

    @Test
    void cyclicalYearUsesGregorianLunarYear() {
        assertThat(CwaLunarCalendarProvider.cyclicalYear(2026)).isEqualTo("丙午");
        assertThat(CwaLunarCalendarProvider.cyclicalYear(2033)).isEqualTo("癸丑");
    }

    private CwaLunarCalendarProvider provider(String key) {
        CwaProperties properties = new CwaProperties(
                "http://localhost:" + server.getAddress().getPort() + "/api",
                key, Duration.ofSeconds(2));
        return new CwaLunarCalendarProvider(RestClient.builder(), properties);
    }

    private void respond(int status, String body) {
        server.createContext("/fileapi/v1/opendataapi/A-A0087-001", exchange -> {
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/csv; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static String csv(String date, String lunarYear, String month, String day) {
        return "\ufeffGregorianCalendar,RepublicOfChinaCalendar,AgriculturalCalendar,"
                + "AgriculturalCalendarMonth,AgriculturalCalendarDay,Week\n"
                + "%s,115,%s,%s,%s,5\n".formatted(date, lunarYear, month, day);
    }
}
