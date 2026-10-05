package io.kestra.plugin.sent.contacts;

import java.util.List;
import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.sent.AbstractSentSingleTask;
import io.kestra.plugin.sent.SentFetchOutput;
import io.kestra.plugin.sent.SentValidation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
@Schema(title = "Get a Sent contact", description = "Fetches one contact by its Sent UUID.")
@Plugin(examples = @Example(title = "Fetch a contact", full = true, code = """
    id: get_sent_contact
    namespace: company.messaging

    inputs:
      - id: contact_id
        type: STRING

    tasks:
      - id: get_contact
        type: io.kestra.plugin.sent.contacts.GetContact
        apiKey: "{{ secret('SENT_API_KEY') }}"
        contactId: "{{ inputs.contact_id }}"
    """))
public class GetContact extends AbstractSentSingleTask implements RunnableTask<SentFetchOutput> {
    @Schema(title = "Contact ID", description = "Sent contact UUID.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> contactId;

    @Override
    public SentFetchOutput run(RunContext runContext) throws Exception {
        var rId = SentValidation.required(runContext.render(contactId).as(String.class).orElse(null), "contactId");
        try (var client = client(runContext)) {
            return output(runContext, client.get(List.of("contacts", rId), Map.of()).data());
        }
    }
}
