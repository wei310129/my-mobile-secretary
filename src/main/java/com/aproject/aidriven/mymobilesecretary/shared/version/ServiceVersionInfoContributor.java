package com.aproject.aidriven.mymobilesecretary.shared.version;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

/** Publishes the exact source/build identity of the currently running JVM. */
@Component
public final class ServiceVersionInfoContributor implements InfoContributor {

    private final Map<String, Object> details;

    public ServiceVersionInfoContributor(
            GitProperties gitProperties, BuildProperties buildProperties, Clock clock) {
        this.details = buildDetails(gitProperties, buildProperties, clock.instant());
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("service", details);
    }

    static Map<String, Object> buildDetails(
            GitProperties gitProperties, BuildProperties buildProperties, Instant startedAt) {
        long buildNumber = positiveBuildNumber(gitProperties.get("total.commit.count"));
        String gitSha = required(gitProperties.get("commit.id.full"), "git commit SHA");
        String shortGitSha = required(gitProperties.get("commit.id.abbrev"), "short git commit SHA");
        String changeSummary = required(
                gitProperties.get("commit.message.short"), "git commit summary");
        boolean dirty = Boolean.parseBoolean(required(gitProperties.get("dirty"), "git dirty state"));
        Instant commitTime = gitProperties.getCommitTime();
        Instant buildTime = buildProperties.getTime();
        if (commitTime == null || buildTime == null) {
            throw new IllegalStateException("Git commit time and build time are required");
        }

        Map<String, Object> version = new LinkedHashMap<>();
        version.put("buildNumber", buildNumber);
        version.put("versionLabel", buildNumber + "-" + shortGitSha + (dirty ? "-dirty" : ""));
        version.put("gitSha", gitSha);
        version.put("shortGitSha", shortGitSha);
        version.put("changeSummary", changeSummary);
        version.put("dirty", dirty);
        version.put("commitTime", commitTime);
        version.put("buildTime", buildTime);
        version.put("startedAt", startedAt);
        return Map.copyOf(version);
    }

    private static long positiveBuildNumber(String raw) {
        try {
            long value = Long.parseLong(required(raw, "git commit count"));
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Git commit count must be a positive integer", exception);
        }
        throw new IllegalStateException("Git commit count must be a positive integer");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " is required");
        }
        return value;
    }
}
