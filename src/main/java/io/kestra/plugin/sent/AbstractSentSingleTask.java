package io.kestra.plugin.sent;

import java.util.Map;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
public abstract class AbstractSentSingleTask extends AbstractSentConnection {
    @Schema(
        title = "Fetch type",
        description = "`FETCH_ONE` returns the resource as `row`, `FETCH` returns a one-item `rows` list, `STORE` writes it to internal storage, and `NONE` returns only `size`."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH_ONE);

    protected SentFetchOutput output(RunContext runContext, Map<String, Object> row) throws Exception {
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH_ONE);
        return SentFetchSupport.single(runContext, rFetchType, row);
    }
}
