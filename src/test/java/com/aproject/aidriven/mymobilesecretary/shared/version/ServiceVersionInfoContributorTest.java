package com.aproject.aidriven.mymobilesecretary.shared.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

class ServiceVersionInfoContributorTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-02T03:04:05Z");

    @Test
    void publishesReadableAndCanonicalCleanVersion() {
        Map<String, Object> details = ServiceVersionInfoContributor.buildDetails(
                gitProperties("199", "false"), buildProperties(), STARTED_AT);

        assertThat(details)
                .containsEntry("buildNumber", 199L)
                .containsEntry("versionLabel", "199-20de79679f41")
                .containsEntry("gitSha", "20de79679f41f3144b8a56afe7d462fa10be01a7")
                .containsEntry("shortGitSha", "20de79679f41")
                .containsEntry("changeSummary", "feat: expose running service version")
                .containsEntry("dirty", false)
                .containsEntry("commitTime", Instant.parse("2026-07-30T11:40:02Z"))
                .containsEntry("buildTime", Instant.parse("2026-08-02T02:03:04Z"))
                .containsEntry("startedAt", STARTED_AT);
    }

    @Test
    void marksUncommittedBuildInVersionLabel() {
        Map<String, Object> details = ServiceVersionInfoContributor.buildDetails(
                gitProperties("199", "true"), buildProperties(), STARTED_AT);

        assertThat(details)
                .containsEntry("versionLabel", "199-20de79679f41-dirty")
                .containsEntry("dirty", true);
    }

    @Test
    void rejectsMissingOrInvalidCommitCount() {
        assertThatThrownBy(() -> ServiceVersionInfoContributor.buildDetails(
                        gitProperties("0", "false"), buildProperties(), STARTED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("positive integer");
    }

    private static GitProperties gitProperties(String count, String dirty) {
        Properties properties = new Properties();
        properties.setProperty("total.commit.count", count);
        properties.setProperty("commit.id.full", "20de79679f41f3144b8a56afe7d462fa10be01a7");
        properties.setProperty("commit.id.abbrev", "20de79679f41");
        properties.setProperty("commit.message.short", "feat: expose running service version");
        properties.setProperty("commit.time", "2026-07-30T11:40:02Z");
        properties.setProperty("dirty", dirty);
        return new GitProperties(properties);
    }

    private static BuildProperties buildProperties() {
        Properties properties = new Properties();
        properties.setProperty("time", "2026-08-02T02:03:04Z");
        return new BuildProperties(properties);
    }
}
