package io.kestra.plugin.sent.templates;

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
@Schema(title = "Get a Sent template", description = "Fetches one template by Sent UUID.")
@Plugin(examples = @Example(title = "Fetch a template", full = true, code = """
    id: get_sent_template
    namespace: company.messaging

    inputs:
      - id: template_id
        type: STRING

    tasks:
      - id: get_template
        type: io.kestra.plugin.sent.templates.GetTemplate
        apiKey: "{{ secret('SENT_API_KEY') }}"
        templateId: "{{ inputs.template_id }}"
    """))
public class GetTemplate extends AbstractSentSingleTask implements RunnableTask<SentFetchOutput> {
    @Schema(title = "Template ID", description = "Sent template UUID.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> templateId;

    @Override
    public SentFetchOutput run(RunContext runContext) throws Exception {
        String rId = SentValidation.required(runContext.render(templateId).as(String.class).orElse(null), "templateId");
        try (var client = client(runContext)) {
            return output(runContext, client.get(List.of("templates", rId), Map.of()).data());
        }
    }
}
