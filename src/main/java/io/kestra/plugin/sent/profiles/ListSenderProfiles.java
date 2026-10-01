package io.kestra.plugin.sent.profiles;

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
@Schema(title = "List Sent Sender Profiles", description = "Lists Sender Profiles available to the API key using the current `/sender-profiles` v3 endpoint.")
@Plugin(examples = @Example(title = "List Sender Profiles", full = true, code = """
    id: sent_sender_profiles
    namespace: company.messaging

    tasks:
      - id: list_profiles
        type: io.kestra.plugin.sent.profiles.ListSenderProfiles
        apiKey: "{{ secret('SENT_ORGANIZATION_API_KEY') }}"
    """))
public class ListSenderProfiles extends AbstractSentListTask implements RunnableTask<SentFetchOutput> {
    @Schema(title = "Search", description = "Optional Sender Profile search text.")
    @PluginProperty(group = "processing")
    private Property<String> search;

    @Override
    public SentFetchOutput run(RunContext runContext) throws Exception {
        String rSearch = runContext.render(search).as(String.class).orElse(null);
        try (var client = client(runContext, false)) {
            return output(runContext, "sender_profiles", (page, pageSize) ->
            {
                var query = new LinkedHashMap<String, Object>();
                query.put("page", page);
                query.put("page_size", pageSize);
                query.put("search", rSearch);
                return client.get(List.of("sender-profiles"), query);
            });
        }
    }
}
