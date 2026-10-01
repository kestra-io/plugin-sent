package io.kestra.plugin.sent.numbers;

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
@Schema(title = "Get Sent phone-number details", description = "Looks up normalized phone-number and carrier metadata using Sent's v3 number lookup endpoint.")
@Plugin(examples = @Example(title = "Look up a phone number", full = true, code = """
    id: sent_number_lookup
    namespace: company.messaging

    inputs:
      - id: phone
        type: STRING

    tasks:
      - id: lookup
        type: io.kestra.plugin.sent.numbers.GetPhoneNumberDetails
        apiKey: "{{ secret('SENT_API_KEY') }}"
        phoneNumber: "{{ inputs.phone }}"
    """))
public class GetPhoneNumberDetails extends AbstractSentSingleTask implements RunnableTask<SentFetchOutput> {
    @Schema(title = "Phone number", description = "Phone number in E.164 format.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> phoneNumber;

    @Override
    public SentFetchOutput run(RunContext runContext) throws Exception {
        String rPhone = SentValidation.e164(runContext.render(phoneNumber).as(String.class).orElse(null), "phoneNumber");
        try (var client = client(runContext)) {
            return output(runContext, client.get(List.of("numbers", "lookup", rPhone), Map.of()).data());
        }
    }
}
