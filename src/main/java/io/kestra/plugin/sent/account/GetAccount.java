package io.kestra.plugin.sent.account;

import java.util.List;
import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.sent.AbstractSentSingleTask;
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
@Schema(title = "Get the authenticated Sent account", description = "Fetches account and plan metadata for the configured Sent API key.")
@Plugin(examples = @Example(title = "Fetch the current Sent account", full = true, code = """
    id: sent_account
    namespace: company.messaging

    tasks:
      - id: get_account
        type: io.kestra.plugin.sent.account.GetAccount
        apiKey: "{{ secret('SENT_API_KEY') }}"
    """))
public class GetAccount extends AbstractSentSingleTask implements RunnableTask<SentFetchOutput> {
    @Override
    public SentFetchOutput run(RunContext runContext) throws Exception {
        try (var client = client(runContext)) {
            return output(runContext, client.get(List.of("me"), Map.of()).data());
        }
    }
}
