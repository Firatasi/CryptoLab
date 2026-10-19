package com.crypto.simulationservice.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record AcceptedOrderResponse(UUID commandId, String status, String statusUrl, Instant acceptedAt) {
    // JSON alan adları kaynak sözleşmeyle aynıdır. 202, fill gerçekleşti demek değildir.

}
