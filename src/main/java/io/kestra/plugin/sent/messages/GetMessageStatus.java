package io.kestra.plugin.sent.messages;

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
@Schema(title = "Get Sent message status", description = "Fetches the current status and metadata for one Sent message ID.")
@Plugin(examples = @Example(title = "Fetch message status", full = true, code = """
    id: get_sent_message
    namespace: company.messaging

    inputs:
      - id: message_id
        type: STRING

    tasks:
      - id: get_message
        type: io.kestra.plugin.sent.messages.GetMessageStatus
        apiKey: "{{ secret('SENT_API_KEY') }}"
        messageId: "{{ inputs.message_id }}"
    """))
public class GetMessageStatus extends AbstractSentSingleTask implements RunnableTask<SentFetchOutput> {
    @Schema(title = "Message ID", description = "Sent message UUID returned by SendMessage.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> messageId;

    @Override
    public SentFetchOutput run(RunContext runContext) throws Exception {
        var rId = SentValidation.required(runContext.render(messageId).as(String.class).orElse(null), "messageId");
        try (var client = client(runContext)) {
            return output(runContext, client.get(List.of("messages", rId), Map.of()).data());
        }
    }
}
