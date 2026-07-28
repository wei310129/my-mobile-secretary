package com.aproject.aidriven.mymobilesecretary.booking.provider.spi;

import java.util.Objects;

public record ChangeProposal(boolean available, String publicSummary) {

    public ChangeProposal {
        publicSummary = Objects.requireNonNull(publicSummary, "publicSummary");
    }
}
