package io.kestra.plugin.sent;

import java.net.URI;
import java.util.List;
import java.util.Map;

import io.kestra.core.models.tasks.Output;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class SentFetchOutput implements Output {
    @Schema(title = "Fetched Sent resources", description = "Populated only with `fetchType: FETCH`.")
    private final List<Map<String, Object>> rows;

    @Schema(title = "First fetched Sent resource", description = "Populated only with `fetchType: FETCH_ONE`.")
    private final Map<String, Object> row;

    @Schema(title = "Stored resource URI", description = "Kestra internal-storage URI populated only with `fetchType: STORE`.")
    private final URI uri;

    @Schema(title = "Fetched resource count")
    private final Long size;
}
