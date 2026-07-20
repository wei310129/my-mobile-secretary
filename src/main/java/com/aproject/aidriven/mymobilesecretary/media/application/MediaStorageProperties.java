package com.aproject.aidriven.mymobilesecretary.media.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** Local development storage settings; the backend can later be replaced by S3-compatible storage. */
@ConfigurationProperties("app.media.storage")
public record MediaStorageProperties(
        @DefaultValue("./var/media") String root,
        @DefaultValue("15MB") DataSize maxFileSize,
        @DefaultValue("0B") DataSize defaultActorQuota
) {
}
