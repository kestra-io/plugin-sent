package io.kestra.plugin.sent.contacts;

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
@Schema(title = "List Sent contacts", description = "Lists and automatically paginates contacts, with optional search and channel filters.")
@Plugin(examples = @Example(title = "Store every matching contact", full = true, code = """
    id: export_sent_contacts
    namespace: company.messaging

    tasks:
      - id: list_contacts
        type: io.kestra.plugin.sent.contacts.ListContacts
        apiKey: "{{ secret('SENT_API_KEY') }}"
        search: customer
        fetchType: STORE
    """))
public class ListContacts extends AbstractSentListTask implements RunnableTask<SentFetchOutput> {
    @Schema(title = "Search", description = "Optional Sent contact search text.")
    @PluginProperty(group = "processing")
    private Property<String> search;

    @Schema(title = "Channel", description = "Optional contact channel filter supported by Sent.")
    @PluginProperty(group = "processing")
    private Property<String> channel;

    @Schema(title = "Phone number", description = "Optional exact phone-number filter.")
    @PluginProperty(group = "processing")
    private Property<String> phone;

    @Override
    public SentFetchOutput run(RunContext runContext) throws Exception {
        var rSearch = runContext.render(search).as(String.class).orElse(null);
        var rChannel = runContext.render(channel).as(String.class).orElse(null);
        var rPhone = runContext.render(phone).as(String.class).orElse(null);
        try (var client = client(runContext)) {
            return output(runContext, "contacts", (page, pageSize) ->
            {
                var query = new LinkedHashMap<String, Object>();
                query.put("page", page);
                query.put("page_size", pageSize);
                query.put("search", rSearch);
                query.put("channel", rChannel);
                query.put("phone", rPhone);
                return client.get(List.of("contacts"), query);
            });
        }
    }
}
