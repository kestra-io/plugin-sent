package io.kestra.plugin.sent.contacts;

import java.util.LinkedHashMap;
import java.util.List;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.sent.AbstractSentConnection;
import io.kestra.plugin.sent.SentTaskOutput;
import io.kestra.plugin.sent.SentValidation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
@Schema(title = "Update a Sent contact", description = "Updates the contact's default channel and/or opt-out state with an idempotency key.")
@Plugin(examples = @Example(title = "Validate an opt-out update in sandbox mode", full = true, code = """
    id: update_sent_contact_sandbox
    namespace: company.messaging

    inputs:
      - id: contact_id
        type: STRING

    tasks:
      - id: update_contact
        type: io.kestra.plugin.sent.contacts.UpdateContact
        apiKey: "{{ secret('SENT_API_KEY') }}"
        contactId: "{{ inputs.contact_id }}"
        optOut: true
        sandbox: true
    """))
public class UpdateContact extends AbstractSentConnection implements RunnableTask<SentTaskOutput> {
    @Schema(title = "Contact ID", description = "Sent contact UUID.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> contactId;

    @Schema(title = "Default channel", description = "New default channel, if it should change.")
    @PluginProperty(group = "main")
    private Property<String> defaultChannel;

    @Schema(title = "Opt out", description = "New contact opt-out state, if it should change.")
    @PluginProperty(group = "main")
    private Property<Boolean> optOut;

    @Schema(title = "Sandbox mode", description = "When true, Sent validates the mutation without changing production data.")
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Boolean> sandbox = Property.ofValue(false);

    @Schema(title = "Idempotency key", description = "Stable key that makes retries safe. The default is unique and stable for this task run.")
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<String> idempotencyKey = Property.ofExpression("sent_{{ taskrun.id }}");

    @Override
    public SentTaskOutput run(RunContext runContext) throws Exception {
        String rId = SentValidation.required(runContext.render(contactId).as(String.class).orElse(null), "contactId");
        String rChannel = runContext.render(defaultChannel).as(String.class).orElse(null);
        Boolean rOptOut = runContext.render(optOut).as(Boolean.class).orElse(null);
        if (rChannel == null && rOptOut == null) {
            throw new IllegalArgumentException("Set defaultChannel and/or optOut.");
        }
        var body = new LinkedHashMap<String, Object>();
        if (rChannel != null) {
            body.put("default_channel", SentValidation.channels(List.of(rChannel)).getFirst());
        }
        if (rOptOut != null) {
            body.put("opt_out", rOptOut);
        }
        body.put("sandbox", runContext.render(sandbox).as(Boolean.class).orElse(false));
        String rKey = SentValidation.idempotencyKey(runContext.render(idempotencyKey).as(String.class).orElse(null));
        try (var client = client(runContext)) {
            return SentTaskOutput.from(client.patch(List.of("contacts", rId), body, rKey));
        }
    }
}
