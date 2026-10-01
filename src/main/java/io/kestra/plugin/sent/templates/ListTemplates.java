package io.kestra.plugin.sent.templates;

import java.util.LinkedHashMap;
import java.util.List;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.sent.AbstractSentListTask;
import io.kestra.plugin.sent.SentFetchOutput;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(title = "List Sent templates", description = "Lists and automatically paginates templates, with optional search, status, and category filters.")
@Plugin(examples = @Example(title = "List approved templates", full = true, code = """
    id: sent_templates
    namespace: company.messaging

    tasks:
      - id: list_templates
        type: io.kestra.plugin.sent.templates.ListTemplates
        apiKey: "{{ secret('SENT_API_KEY') }}"
        status: approved
        fetchType: FETCH
    """))
public class ListTemplates extends AbstractSentListTask implements RunnableTask<SentFetchOutput> {
    @Schema(title = "Search", description = "Optional template search text.")
    @PluginProperty(group = "processing")
    private Property<String> search;

    @Schema(title = "Status", description = "Optional Sent template-status filter.")
    @PluginProperty(group = "processing")
    private Property<String> status;

    @Schema(title = "Category", description = "Optional Sent template-category filter.")
    @PluginProperty(group = "processing")
    private Property<String> category;

    @Override
    public SentFetchOutput run(RunContext runContext) throws Exception {
        String rSearch = runContext.render(search).as(String.class).orElse(null);
        String rStatus = runContext.render(status).as(String.class).orElse(null);
        String rCategory = runContext.render(category).as(String.class).orElse(null);
        try (var client = client(runContext)) {
            return output(runContext, "templates", (page, pageSize) ->
            {
                var query = new LinkedHashMap<String, Object>();
                query.put("page", page);
                query.put("page_size", pageSize);
                query.put("search", rSearch);
                query.put("status", rStatus);
                query.put("category", rCategory);
                return client.get(List.of("templates"), query);
            });
        }
    }
}
