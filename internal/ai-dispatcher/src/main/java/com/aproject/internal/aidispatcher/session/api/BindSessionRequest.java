package com.aproject.internal.aidispatcher.session.api;

record BindSessionRequest(
        String externalSessionId,
        Long expectedVersion,
        String reason
) {
}
