package io.kestra.plugin.sent.profiles;

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
@Schema(title = "Get a Sent Sender Profile", description = "Fetches one Sender Profile by UUID from the current v3 endpoint.")
@Plugin(examples = @Example(title = "Fetch a Sender Profile", full = true, code = """
    id: get_sent_sender_profile
    namespace: company.messaging

    inputs:
      - id: sender_profile_id
        type: STRING

    tasks:
      - id: get_profile
        type: io.kestra.plugin.sent.profiles.GetSenderProfile
        apiKey: "{{ secret('SENT_ORGANIZATION_API_KEY') }}"
        senderProfileId: "{{ inputs.sender_profile_id }}"
    """))
public class GetSenderProfile extends AbstractSentSingleTask implements RunnableTask<SentFetchOutput> {
    @Schema(title = "Sender Profile ID", description = "Sent Sender Profile UUID.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> senderProfileId;

    @Override
    public SentFetchOutput run(RunContext runContext) throws Exception {
        var rId = SentValidation.required(runContext.render(senderProfileId).as(String.class).orElse(null), "senderProfileId");
        try (var client = client(runContext, false)) {
            return output(runContext, client.get(List.of("sender-profiles", rId), Map.of()).data());
        }
    }
}
