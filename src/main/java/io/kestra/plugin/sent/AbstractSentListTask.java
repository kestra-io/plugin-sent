package io.kestra.plugin.sent;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
public abstract class AbstractSentListTask extends AbstractSentConnection {
    @Schema(title = "Starting page", description = "First Sent page to fetch, starting at 1.")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(1) Integer> page = Property.ofValue(1);

    @Schema(title = "Page size", description = "Resources requested per Sent API page, from 1 to 100.")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(100) Integer> pageSize = Property.ofValue(100);

    @Schema(title = "Maximum pages", description = "Safety limit that prevents an unbounded pagination loop.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<@Min(1) @Max(10000) Integer> maxPages = Property.ofValue(1000);

    @Schema(
        title = "Fetch type",
        description = "`FETCH_ONE` returns the first resource, `FETCH` returns all resources, `STORE` streams all resources to internal storage, and `NONE` returns only the count."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    protected SentFetchOutput output(RunContext runContext, String itemKey, SentFetchSupport.PageLoader loader) throws Exception {
        int rPage = runContext.render(page).as(Integer.class).orElse(1);
        int rPageSize = runContext.render(pageSize).as(Integer.class).orElse(100);
        int rMaxPages = runContext.render(maxPages).as(Integer.class).orElse(1000);
        FetchType rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        if (rPage < 1 || rPageSize < 1 || rPageSize > 100 || rMaxPages < 1 || rMaxPages > 10000) {
            throw new IllegalArgumentException("page, pageSize, or maxPages is outside its documented range.");
        }
        return SentFetchSupport.list(runContext, rFetchType, rPage, rPageSize, rMaxPages, itemKey, loader);
    }
}
