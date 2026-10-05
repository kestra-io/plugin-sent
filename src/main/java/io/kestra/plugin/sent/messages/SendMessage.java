package io.kestra.plugin.sent.messages;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
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
@Schema(
    title = "Send a message with Sent",
    description = "Sends text or a Sent template to one or more E.164 recipients. The task enforces mutually exclusive text/template content and uses an idempotency key so transient retries cannot duplicate a completed send."
)
@Plugin(
    examples = {
        @Example(title = "Validate a text message without delivery", full = true, code = """
            id: sent_text_sandbox
            namespace: company.messaging

            inputs:
              - id: recipient
                type: STRING

            tasks:
              - id: send_message
                type: io.kestra.plugin.sent.messages.SendMessage
                apiKey: "{{ secret('SENT_API_KEY') }}"
                to:
                  - "{{ inputs.recipient }}"
                channels:
                  - sent
                  - sms
                text: "Your test notification is ready."
                sandbox: true
            """),
        @Example(title = "Validate a template message without delivery", full = true, code = """
            id: sent_template_sandbox
            namespace: company.messaging

            inputs:
              - id: recipient
                type: STRING

            tasks:
              - id: send_template
                type: io.kestra.plugin.sent.messages.SendMessage
                apiKey: "{{ secret('SENT_API_KEY') }}"
                to:
                  - "{{ inputs.recipient }}"
                template:
                  name: order_update
                  parameters:
                    order_number: "TEST-123"
                sandbox: true
            """)
    }
)
public class SendMessage extends AbstractSentConnection implements RunnableTask<SentTaskOutput> {
    @Schema(title = "Recipients", description = "One or more destination phone numbers in E.164 format.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<String>> to;

    @Schema(title = "Channels", description = "Ordered Sent channel fallback list: sent, sms, whatsapp, and/or rcs.")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<List<String>> channels = Property.ofValue(List.of("sent"));

    @Schema(title = "Text", description = "Plain message text. Set exactly one of text or template.")
    @PluginProperty(group = "main")
    private Property<String> text;

    @Schema(title = "Template", description = "Sent template reference and optional parameters. Set exactly one of text or template.")
    @Valid
    @PluginProperty(group = "main")
    private MessageTemplate template;

    @Schema(title = "Sandbox mode", description = "When true, Sent validates and simulates the send without delivering a production message.")
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Boolean> sandbox = Property.ofValue(false);

    @Schema(
        title = "Idempotency key",
        description = "Stable key for this logical send. Sent caches completed mutation responses for 24 hours. The default `sent_{{ taskrun.id }}` reuses the cached response when the same task run is deliberately re-sent; use a new key for an intentional new send."
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<String> idempotencyKey = Property.ofExpression("sent_{{ taskrun.id }}");

    @Override
    public SentTaskOutput run(RunContext runContext) throws Exception {
        var rRecipients = SentValidation.recipients(runContext.render(to).asList(String.class));
        var rChannels = SentValidation.channels(runContext.render(channels).asList(String.class));
        var rText = runContext.render(text).as(String.class).orElse(null);
        rText = rText == null || rText.isBlank() ? null : rText;

        if ((rText == null) == (template == null)) {
            throw new IllegalArgumentException("Set exactly one of text or template.");
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("to", rRecipients);
        body.put("channel", rChannels);
        if (rText != null) {
            body.put("text", rText);
        } else {
            body.put("template", renderTemplate(runContext));
        }
        body.put("sandbox", runContext.render(sandbox).as(Boolean.class).orElse(false));
        var rKey = SentValidation.idempotencyKey(runContext.render(idempotencyKey).as(String.class).orElse(null));

        try (var client = client(runContext)) {
            return SentTaskOutput.from(client.post(List.of("messages"), body, rKey));
        }
    }

    private Map<String, Object> renderTemplate(RunContext runContext) throws Exception {
        var rId = runContext.render(template.getId()).as(String.class).orElse(null);
        var rName = runContext.render(template.getName()).as(String.class).orElse(null);
        rId = rId == null || rId.isBlank() ? null : rId;
        rName = rName == null || rName.isBlank() ? null : rName;
        if ((rId == null) == (rName == null)) {
            throw new IllegalArgumentException("template must set exactly one of id or name.");
        }
        var rTemplate = new LinkedHashMap<String, Object>();
        if (rId != null) {
            rTemplate.put("id", rId);
        } else {
            rTemplate.put("name", rName);
        }
        var rParameters = runContext.render(template.getParameters()).asMap(String.class, Object.class);
        if (rParameters != null && !rParameters.isEmpty()) {
            rTemplate.put("parameters", rParameters);
        }
        return rTemplate;
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageTemplate {
        @Schema(title = "Template ID", description = "Sent template UUID. Mutually exclusive with name.")
        @PluginProperty(group = "main")
        private Property<String> id;

        @Schema(title = "Template name", description = "Sent template name. Mutually exclusive with id.")
        @PluginProperty(group = "main")
        private Property<String> name;

        @Schema(title = "Template parameters", description = "Template placeholder values. Avoid placing unnecessary personal data in workflow logs or labels.")
        @PluginProperty(group = "main")
        private Property<Map<String, Object>> parameters;
    }
}
