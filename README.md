# Kestra plugin for Sent

Native [Kestra](https://kestra.io) tasks and a signed webhook trigger for the [Sent v3 API](https://docs.sent.dm/reference/api). The plugin is intentionally API-native: it models Sent resources as typed Kestra components instead of exposing a generic HTTP escape hatch.

## Components

| Package | Component | Operation |
| --- | --- | --- |
| `account` | `GetAccount` | Read authenticated account and plan metadata |
| `contacts` | `ListContacts` | Filter and automatically paginate contacts |
| `contacts` | `GetContact` | Fetch one contact |
| `contacts` | `CreateContact` | Create a contact with sandbox and idempotency support |
| `contacts` | `UpdateContact` | Update channel or opt-out state with sandbox and idempotency support |
| `messages` | `SendMessage` | Send text or template content with channel fallback |
| `messages` | `GetMessageStatus` | Read current message status |
| `messages` | `GetMessageActivities` | Read message delivery history |
| `profiles` | `ListSenderProfiles` | List current v3 Sender Profiles |
| `profiles` | `GetSenderProfile` | Fetch one Sender Profile |
| `templates` | `ListTemplates` | Filter and automatically paginate templates |
| `templates` | `GetTemplate` | Fetch one template |
| `numbers` | `GetPhoneNumberDetails` | Look up carrier and normalized number metadata |
| `triggers` | `EventTrigger` | Start a flow from an HMAC-verified Sent event |

## Compatibility

| Plugin line | Kestra | Java | Sent API |
| --- | --- | --- | --- |
| `1.x` | `2.0.x` | 25 | v3 |

The build pins its Kestra platform version in `gradle.properties`. Re-run the [upgrade checklist](UPGRADE-CHECKLIST.md) before changing it.

Kestra 1.3 is not supported by this implementation. The signed trigger uses the Kestra 2.0 raw-body webhook contract and asynchronous execution API; the CI workflow explicitly selects Java 25.

## Install

Build the shaded plugin JAR:

```bash
./gradlew clean shadowJar
```

The artifact is written under `build/libs/`. Copy it into the `plugins/` directory of every Kestra server and worker that may validate or execute these components, then restart those processes. For a container deployment, mount the same JAR read-only into `/app/plugins/`.

Do not install a second copy of the same plugin at a different version. Confirm the component catalogue shows `io.kestra.plugin.sent` after restart.

## Authentication and Sender Profiles

Every API task requires:

```yaml
apiKey: "{{ secret('SENT_API_KEY') }}"
```

The value is sent only in the `x-api-key` header and is excluded from task string representations. Do not place it directly in flow YAML, labels, inputs, or logs.

An organization API key can scope account, contact, message, template, and number calls to a child Sender Profile:

```yaml
profileId: "{{ secret('SENT_PROFILE_ID') }}"
```

This becomes `x-profile-id`. Profile API keys must leave `profileId` unset. Sender Profile discovery itself is organization-scoped, so the two `profiles` tasks deliberately do not send `x-profile-id`.

## Send safely

`SendMessage`, `CreateContact`, and `UpdateContact` accept `sandbox`. Set it to `true` in development, CI, and initial production validation:

```yaml
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
```

All mutations require an `Idempotency-Key`. The default, `sent_{{ taskrun.id }}`, remains stable across retries of one task run. For retries that may occur in a new execution, supply a key derived from a stable business identifier instead:

```yaml
idempotencyKey: "order_{{ inputs.order_id }}"
```

Sent caches a completed response for 24 hours per key. It does not compare request bodies on replay, so never reuse a key for different logical content. Concurrent duplicates may return `409`; serialize competing sends for the same key when possible.

The output reports `idempotentReplayed` from `Idempotent-Replayed` and `sandbox` from `X-Sandbox`, along with `requestId` and the documented response `data`.

## Large lists and pagination

List tasks page until Sent returns `pagination.has_more: false`. They validate page metadata and enforce `maxPages` to prevent an unbounded loop.

Choose `fetchType` based on downstream use:

- `FETCH_ONE`: return the first item as `row`.
- `FETCH`: return all items as `rows`.
- `STORE`: stream binary Ion to Kestra internal storage and return `uri`; recommended for large results.
- `NONE`: traverse the result and return only `size`.

## Signed webhook trigger

`EventTrigger` uses Kestra's native webhook route:

```text
/api/v1/{tenant}/executions/webhook/{namespace}/{flowId}/{key}
```

Configure the full externally reachable HTTPS URL manually in Sent. Automatic endpoint creation is intentionally not included because the plugin cannot reliably infer a deployment's public URL or lifecycle ownership.

```yaml
id: sent_inbound_events
namespace: company.messaging

tasks:
  - id: route_event
    type: io.kestra.plugin.core.log.Log
    message: "Verified {{ trigger.event }}; dedupe={{ trigger.dedupeKey }}"

triggers:
  - id: sent_webhook
    type: io.kestra.plugin.sent.triggers.EventTrigger
    key: "{{ secret('SENT_WEBHOOK_URL_KEY') }}"
    signingSecret: "{{ secret('SENT_WEBHOOK_SIGNING_SECRET') }}"
    events:
      - message.received
```

The trigger:

1. Requires `X-Webhook-ID`, `X-Webhook-Timestamp`, and `X-Webhook-Signature`.
2. Verifies `v1,base64(HMAC-SHA256(base64_decode(secret without whsec_), id.timestamp.raw_body))` before parsing JSON.
3. Compares signatures in constant time and rejects timestamps outside the configured five-minute default window.
4. Accepts exact events, a parent field such as `message`, or `*`.
5. Returns a payload-derived `dedupeKey`.

Sent delivery is at least once. `dedupeKey` is advisory: if processing must be exactly-once at the business layer, atomically claim it in shared persistent storage (for example a database unique key or Redis `SET NX`) before applying side effects. `X-Webhook-ID` identifies the endpoint configuration and is not a per-delivery ID.

The trigger always uses raw-body `FETCH`; `STORE` and `NONE` would make signature verification impossible. Keep the URL key and signing secret separate, rotate both through your normal secret-management process, and update the Sent endpoint immediately when rotating.

## Data handling

Sent payloads can contain phone numbers, message bodies, and template parameters. The plugin never logs request bodies, response bodies, raw webhook bodies, authentication headers, or signatures. Provider failures are mapped to status, error code, request ID, and a bounded/redacted message.

Operators remain responsible for lawful basis, consent and opt-out handling, retention, data-subject workflows, regional restrictions, and any required data-processing agreements. Prefer `STORE` for large exports, avoid copying payloads into logs or labels, and configure Kestra execution retention and storage encryption to match organizational policy.

## Reliability model

- Custom base URLs require HTTPS, except loopback HTTP used by tests.
- Redirects are disabled and Kestra's internal HTTP client retains its SSRF destination guard.
- GET requests retry bounded transient failures.
- Mutations cannot execute without an idempotency key and are therefore retry-safe.
- Exponential delays and `Retry-After` are capped by `maxRetryInterval` at the plugin layer.
- Pagination validates the returned page number and `has_more` type.
- Webhooks return `400` for missing headers/malformed payloads, `401` for bad/stale signatures, `204` for filtered events or unmet trigger conditions, and `200` after queuing an execution.

## Develop and verify

Requirements: Java 25.

```bash
./gradlew spotlessCheck test lintPluginDocs shadowJar
```

Tests use WireMock and Kestra's in-memory test services. They never call the Sent production API. The official fixed Sent signature vector is included in `SentWebhookVerifierTest`.

## Maintainer QA

The review kit under [`qa/`](qa) contains:

- `sent-all-tasks-sandbox-qa.yaml`: an importable manual flow covering all 13 Sent tasks with sandbox-only mutations.
- `sent-webhook-trigger-qa.yaml`: an importable flow dedicated to signed webhook verification.
- `KESTRA-UI-QA.md`: exact setup, execution, and pass/fail instructions for the Kestra UI.
- `REVIEW-RESULTS.md`: dated build/runtime evidence and remaining live-provider QA limitations.
- `send-signed-webhook.py`: a dependency-free positive/tampered signature helper for an approved disposable endpoint.
- `full-sent-local-qa.yaml` and `MockSent.java`: the all-component localhost regression used for recorded Kestra runtime evidence.

Live-provider QA requires only disposable Sent resources. Never use customer IDs, recipients, content, or production webhook endpoints.

See [`examples/`](examples), [`SECURITY.md`](SECURITY.md), and [`CONTRIBUTING.md`](CONTRIBUTING.md).
