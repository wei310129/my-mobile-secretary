package com.aproject.aidriven.mymobilesecretary.booking.availability;

import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.AvailabilitySearchRequest;
import java.util.List;

public interface AvailabilitySource {

    String sourceKey();

    List<AvailabilityCandidate> search(AvailabilitySearchRequest request);
}
