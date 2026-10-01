package io.kestra.plugin.sent;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class SentTaskOutput implements io.kestra.core.models.tasks.Output {
    @Schema(title = "Sent response data", description = "Provider resource data from the successful v3 response envelope.")
    private final Map<String, Object> data;

    @Schema(title = "Sent request ID", description = "Provider request identifier for support and correlation.")
    private final String requestId;

    @Schema(title = "Idempotent replay", description = "Whether Sent returned a cached response for a previously completed idempotent request.")
    private final Boolean idempotentReplayed;

    @Schema(title = "Sandbox response", description = "Whether Sent confirmed that the mutation was simulated without side effects.")
    private final Boolean sandbox;

    public static SentTaskOutput from(SentResponse response) {
        return SentTaskOutput.builder()
            .data(response.data())
            .requestId(response.requestId())
            .idempotentReplayed(response.idempotentReplayed())
            .sandbox(response.sandbox())
            .build();
    }
}
