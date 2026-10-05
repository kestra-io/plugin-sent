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
@Schema(title = "Create a Sent contact", description = "Creates a contact with an idempotent Sent API mutation. Use sandbox mode while validating a flow.")
@Plugin(examples = @Example(title = "Validate contact creation in sandbox mode", full = true, code = """
    id: create_sent_contact_sandbox
    namespace: company.messaging

    inputs:
      - id: phone
        type: STRING

    tasks:
      - id: create_contact
        type: io.kestra.plugin.sent.contacts.CreateContact
        apiKey: "{{ secret('SENT_API_KEY') }}"
        phoneNumber: "{{ inputs.phone }}"
        defaultChannel: sent
        sandbox: true
    """))
public class CreateContact extends AbstractSentConnection implements RunnableTask<SentTaskOutput> {
    @Schema(title = "Phone number", description = "Contact phone number in E.164 format.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> phoneNumber;

    @Schema(title = "Default channel", description = "Optional default delivery channel for the contact.")
    @PluginProperty(group = "main")
    private Property<String> defaultChannel;

    @Schema(title = "Sandbox mode", description = "When true, Sent validates the mutation without creating production data.")
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Boolean> sandbox = Property.ofValue(false);

    @Schema(title = "Idempotency key", description = "Stable key that makes retries safe. The default is unique and stable for this task run.")
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<String> idempotencyKey = Property.ofExpression("sent_{{ taskrun.id }}");

    @Override
    public SentTaskOutput run(RunContext runContext) throws Exception {
        var rPhone = SentValidation.e164(runContext.render(phoneNumber).as(String.class).orElse(null), "phoneNumber");
        var rChannel = runContext.render(defaultChannel).as(String.class).orElse(null);
        if (rChannel != null) {
            rChannel = SentValidation.channels(List.of(rChannel)).getFirst();
        }
        var rKey = SentValidation.idempotencyKey(runContext.render(idempotencyKey).as(String.class).orElse(null));
        var body = new LinkedHashMap<String, Object>();
        body.put("phone_number", rPhone);
        if (rChannel != null) {
            body.put("default_channel", rChannel);
        }
        body.put("sandbox", runContext.render(sandbox).as(Boolean.class).orElse(false));
        try (var client = client(runContext)) {
            return SentTaskOutput.from(client.post(List.of("contacts"), body, rKey));
        }
    }
}
