package com.aproject.aidriven.mymobilesecretary.integration.places;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.integration.IntegrationException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Google Places client 測試(JDK HttpServer 假服務):
 * 解析成功、查無結果回 empty、認證失敗丟明確錯誤——不打真實 Google。
 */
class GooglePlacesClientTest {

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

    private GooglePlacesClient client() {
        String base = "http://localhost:" + server.getAddress().getPort();
        return new GooglePlacesClient(RestClient.builder(),
                new GooglePlacesProperties(true, "test-key", base, Duration.ofSeconds(2)));
    }

    private void stubSearch(int status, String body) {
        server.createContext("/v1/places:searchText", exchange -> {
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test
    void parsesFirstCandidate() {
        stubSearch(200, """
                {"places":[{
                  "displayName": {"text": "全聯福利中心 新店民權店"},
                  "formattedAddress": "231台灣新北市新店區民權路42號",
                  "location": {"latitude": 24.9676, "longitude": 121.5407},
                  "primaryTypeDisplayName": {"text": "超市"}
                }]}
                """);

        Optional<GooglePlacesClient.PlaceCandidate> candidate = client().searchFirst("全聯 新店");

        assertThat(candidate).isPresent();
        assertThat(candidate.get().name()).isEqualTo("全聯福利中心 新店民權店");
        assertThat(candidate.get().address()).contains("民權路42號");
        assertThat(candidate.get().latitude()).isEqualTo(24.9676);
        assertThat(candidate.get().type()).isEqualTo("超市");
    }

    /** Google 查無結果(空 body 或空 places)→ empty,由呼叫端決定訊息。 */
    @Test
    void noResultsReturnsEmpty() {
        stubSearch(200, "{}");

        assertThat(client().searchFirst("不存在的店")).isEmpty();
    }

    @Test
    void parsesRestaurantCandidateWithHospitalityFields() {
        stubSearch(200, """
                {"places":[{
                  "displayName": {"text": "鼎泰豐 信義店"},
                  "formattedAddress": "台北市大安區信義路二段194號",
                  "location": {"latitude": 25.0333, "longitude": 121.5300},
                  "websiteUri": "https://www.dintaifung.com.tw",
                  "googleMapsUri": "https://maps.google.com/?cid=123",
                  "nationalPhoneNumber": "02 2321 8928",
                  "regularOpeningHours": {"weekdayDescriptions": [
                    "星期一: 11:00 – 20:30", "星期二: 11:00 – 20:30"]},
                  "reservable": true,
                  "goodForChildren": true,
                  "accessibilityOptions": {"wheelchairAccessibleEntrance": true}
                }]}
                """);

        Optional<GooglePlacesClient.RestaurantCandidate> candidate =
                client().searchRestaurantFirst("鼎泰豐");

        assertThat(candidate).isPresent();
        assertThat(candidate.get().name()).isEqualTo("鼎泰豐 信義店");
        assertThat(candidate.get().websiteUri()).contains("dintaifung");
        assertThat(candidate.get().phoneNumber()).isEqualTo("02 2321 8928");
        assertThat(candidate.get().openingHours()).hasSize(2).allMatch(day -> day.contains("11:00"));
        assertThat(candidate.get().reservable()).isTrue();
        assertThat(candidate.get().goodForChildren()).isTrue();
        assertThat(candidate.get().wheelchairAccessible()).isTrue();
        // Google 沒回的欄位是「不知道」,不可誤判成 false(明確不支援)
        assertThat(candidate.get().allowsDogs()).isNull();
    }

    @Test
    void restaurantSearchWithoutHospitalityDataStillParses() {
        stubSearch(200, """
                {"places":[{
                  "displayName": {"text": "巷口小吃"},
                  "formattedAddress": "新北市新店區某路1號",
                  "location": {"latitude": 24.96, "longitude": 121.54}
                }]}
                """);

        Optional<GooglePlacesClient.RestaurantCandidate> candidate =
                client().searchRestaurantFirst("巷口小吃");

        assertThat(candidate).isPresent();
        assertThat(candidate.get().websiteUri()).isNull();
        assertThat(candidate.get().openingHours()).isEmpty();
        assertThat(candidate.get().reservable()).isNull();
    }

    @Test
    void authFailureThrowsIntegrationException() {
        stubSearch(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}");

        assertThatThrownBy(() -> client().searchFirst("全聯"))
                .isInstanceOf(IntegrationException.class);
    }

    @Test
    void resolvesFullGoogleMapsPlaceLinkWithoutPlacesCredential() {
        GooglePlacesClient client = new GooglePlacesClient(
                RestClient.builder(),
                new GooglePlacesProperties(false, "", "http://localhost", Duration.ofSeconds(2)),
                current -> Optional.empty());

        Optional<GooglePlacesClient.PlaceCandidate> result = client.resolveMapsLink(
                "https://www.google.com/maps/place/Taipei+Main+Station/@25.0478,121.5170,17z");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().name()).isEqualTo("Taipei Main Station");
        assertThat(result.orElseThrow().latitude()).isEqualTo(25.0478);
        assertThat(result.orElseThrow().longitude()).isEqualTo(121.5170);
    }

    @Test
    void resolvesAllowedShortLinkThroughValidatedRedirect() {
        GooglePlacesClient client = new GooglePlacesClient(
                RestClient.builder(),
                new GooglePlacesProperties(false, "", "http://localhost", Duration.ofSeconds(2)),
                current -> "maps.app.goo.gl".equals(current.getHost())
                        ? Optional.of(URI.create(
                                "https://www.google.com/maps/place/Taoyuan+HSR/@25.0131,121.2142,17z"))
                        : Optional.empty());

        Optional<GooglePlacesClient.PlaceCandidate> result =
                client.resolveMapsLink("https://maps.app.goo.gl/AbCdEf123");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().name()).isEqualTo("Taoyuan HSR");
        assertThat(result.orElseThrow().latitude()).isEqualTo(25.0131);
    }

    @Test
    void resolvesShortLinkThroughMultipleAllowedGoogleRedirects() {
        GooglePlacesClient client = new GooglePlacesClient(
                RestClient.builder(),
                new GooglePlacesProperties(false, "", "http://localhost", Duration.ofSeconds(2)),
                current -> {
                    if ("maps.app.goo.gl".equals(current.getHost())) {
                        return Optional.of(URI.create("https://maps.google.com/?cid=123456789"));
                    }
                    if ("maps.google.com".equals(current.getHost())) {
                        return Optional.of(URI.create(
                                "https://www.google.com/maps/place/Transit+Station/@24.6849,120.9047,17z"));
                    }
                    return Optional.empty();
                });

        Optional<GooglePlacesClient.PlaceCandidate> result =
                client.resolveMapsLink("https://maps.app.goo.gl/GenericShortId?g_st=ic");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().name()).isEqualTo("Transit Station");
        assertThat(result.orElseThrow().latitude()).isEqualTo(24.6849);
        assertThat(result.orElseThrow().longitude()).isEqualTo(120.9047);
    }

    @Test
    void resolvesGoogleMapsQueryAndFtidLinkThroughPlacesSearch() {
        stubSearch(200, """
                {"places":[{
                  "displayName": {"text": "Transit Station"},
                  "formattedAddress": "Miaoli County",
                  "location": {"latitude": 24.6849, "longitude": 120.9047},
                  "primaryTypeDisplayName": {"text": "Bus station"}
                }]}
                """);
        String base = "http://localhost:" + server.getAddress().getPort();
        GooglePlacesClient client = new GooglePlacesClient(
                RestClient.builder(),
                new GooglePlacesProperties(true, "test-key", base, Duration.ofSeconds(2)),
                current -> {
                    if ("maps.app.goo.gl".equals(current.getHost())) {
                        return Optional.of(URI.create("https://maps.google.com/?cid=123456789"));
                    }
                    if ("maps.google.com".equals(current.getHost())) {
                        return Optional.of(URI.create(
                                "https://www.google.com/maps?q=Transit+Station&ftid=0x123:0x456&entry=tts"));
                    }
                    return Optional.empty();
                });

        Optional<GooglePlacesClient.PlaceCandidate> result =
                client.resolveMapsLink("https://maps.app.goo.gl/GenericQueryLink?g_st=ic");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().name()).isEqualTo("Transit Station");
        assertThat(result.orElseThrow().latitude()).isEqualTo(24.6849);
    }

    @Test
    void rejectsNonGoogleInputAndRedirectTarget() {
        assertThat(GooglePlacesClient.isGoogleMapsLink("https://example.com/maps/place/test"))
                .isFalse();
        assertThat(GooglePlacesClient.isGoogleMapsLink(
                "https://www.google.com:8443/maps/place/test/@25.0,121.5"))
                .isFalse();

        GooglePlacesClient client = new GooglePlacesClient(
                RestClient.builder(),
                new GooglePlacesProperties(false, "", "http://localhost", Duration.ofSeconds(2)),
                current -> Optional.of(URI.create("https://attacker.example/collect")));

        assertThatThrownBy(() -> client.resolveMapsLink("https://maps.app.goo.gl/Unsafe"))
                .isInstanceOf(IntegrationException.class);
    }
}
