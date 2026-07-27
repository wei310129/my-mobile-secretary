package com.aproject.aidriven.mymobilesecretary.api.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CalendarApiTest extends IntegrationTestBase {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

    @Test
    void createUsesV2NamespaceAndReturnsPublicBusinessFieldsWithoutInternalIds() throws Exception {
        String request =
                """
                {
                  "title":"裕隆城",
                  "placement":{
                    "kind":"TIMED_INTERVAL",
                    "start":"2026-07-25T03:00:00Z",
                    "end":"2026-07-25T08:00:00Z",
                    "zoneId":"Asia/Taipei"
                  },
                  "category":"親子活動",
                  "onlineLink":"https://meet.google.com/abc",
                  "onlineLinkLabel":"Google Meet",
                  "activities":[],
                  "nodes":[]
                }
                """;
        String body = mockMvc.perform(post("/api/v2/calendar/plans")
                        .header("Idempotency-Key", "calendar-api-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("裕隆城"))
                .andExpect(jsonPath("$.category").value("親子活動"))
                .andExpect(jsonPath("$.onlineLinkHost").value("meet.google.com"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(
                "workspace", "actor", "createdByUserId", "calendar_plan", "CalendarPlanEntity");
        assertThat(UUID_PATTERN.matcher(body).find()).isFalse();
        String replay = mockMvc.perform(post("/api/v2/calendar/plans")
                        .header("Idempotency-Key", "calendar-api-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(replay).isEqualTo(body);
    }

    @Test
    void invalidLinkAndMalformedPlacementReturnSafeErrorsWithoutMutationClaims() throws Exception {
        String invalidLink = mockMvc.perform(post("/api/v2/calendar/plans")
                        .header("Idempotency-Key", "calendar-api-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "title":"危險連結",
                                  "placement":{
                                    "kind":"TIMED_POINT",
                                    "start":"2026-07-25T03:00:00Z",
                                    "zoneId":"Asia/Taipei"
                                  },
                                  "onlineLink":"file:///private/secret"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(invalidLink)
                .doesNotContain("file:///private/secret", "java.", "calendar_online_access_link");

        mockMvc.perform(post("/api/v2/calendar/plans")
                        .header("Idempotency-Key", "calendar-api-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "title":"缺時間",
                                  "placement":{"kind":"TIMED_INTERVAL","zoneId":"Asia/Taipei"}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void publicRevisionEndpointsUseBusinessKeysAndNeverExposePersistenceIdentity()
            throws Exception {
        mockMvc.perform(post("/api/v2/calendar/plans")
                        .header("Idempotency-Key", "calendar-api-revision-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "title":"電影",
                                  "placement":{
                                    "kind":"TIMED_POINT",
                                    "start":"2026-07-25T03:00:00Z",
                                    "zoneId":"Asia/Taipei"
                                  },
                                  "nodes":[{
                                    "nodeKey":"showtime",
                                    "label":"開演",
                                    "expressionKind":"ABSOLUTE",
                                    "absoluteTime":"2026-07-25T03:00:00Z"
                                  }]
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v2/calendar/plans/current/nodes/showtime/move")
                        .header("Plan-Key", "calendar-api-revision-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"time":"2026-07-25T03:30:00Z","expectedRevision":1}
                                """))
                .andExpect(status().isUnprocessableEntity());

        String revision = mockMvc.perform(
                        patch("/api/v2/calendar/plans/current/nodes/showtime/revision")
                                .header("Plan-Key", "calendar-api-revision-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"time":"2026-07-25T03:30:00Z","expectedRevision":1}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeKey").value("showtime"))
                .andExpect(jsonPath("$.revision").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String category = mockMvc.perform(patch("/api/v2/calendar/plans/current/category")
                        .header("Plan-Key", "calendar-api-revision-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"category":" 親子   電影 ","expectedRevision":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("親子 電影"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String link = mockMvc.perform(patch("/api/v2/calendar/plans/current/online-link")
                        .header("Plan-Key", "calendar-api-revision-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"uri":"HTTPS://Meet.Example.com/room","label":"主會議"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onlineLinkHost").value("meet.example.com"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String loaded = mockMvc.perform(get("/api/v2/calendar/plans/current")
                        .header("Plan-Key", "calendar-api-revision-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("電影"))
                .andExpect(jsonPath("$.onlineLinkHost").value("meet.example.com"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(revision + category + link + loaded)
                .doesNotContain(
                        "workspace",
                        "actor",
                        "createdByUserId",
                        "calendar_plan",
                        "CalendarPlanEntity");
        assertThat(UUID_PATTERN.matcher(revision + category + link + loaded).find()).isFalse();
    }

    @Test
    void warmCreatePathMeetsLowComplexityP95Budget() throws Exception {
        var elapsedMillis = new ArrayList<Long>();
        for (int index = 0; index < 10; index++) {
            long started = System.nanoTime();
            mockMvc.perform(post("/api/v2/calendar/plans")
                            .header("Idempotency-Key", "calendar-latency-" + index)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    """
                                    {
                                      "title":"延遲樣本",
                                      "placement":{
                                        "kind":"TIMED_POINT",
                                        "start":"2026-07-26T03:00:00Z",
                                        "zoneId":"Asia/Taipei"
                                      }
                                    }
                                    """))
                    .andExpect(status().isCreated());
            elapsedMillis.add((System.nanoTime() - started) / 1_000_000);
        }
        Collections.sort(elapsedMillis);

        long p95 = elapsedMillis.get(9);
        assertThat(p95).isLessThanOrEqualTo(1_500);
    }
}
