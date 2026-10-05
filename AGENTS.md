# Kestra Sent Plugin

## What

- Provides 13 Sent API tasks and one signed webhook trigger under `io.kestra.plugin.sent`.
- Targets Kestra 1.3.39 LTS and Java 21.

## Why

- Send messages, manage contacts, and read account, message, phone-number, Sender Profile, and template data from Kestra workflows.
- Start workflows from verified Sent webhook events without implementing API authentication, retries, or signature checks in each flow.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.sent`:

- `account`: GetAccount.
- `contacts`: CreateContact, UpdateContact, GetContact, ListContacts.
- `messages`: SendMessage, GetMessageStatus, GetMessageActivities.
- `numbers`: GetPhoneNumberDetails.
- `profiles`: ListSenderProfiles, GetSenderProfile.
- `templates`: ListTemplates, GetTemplate.
- `triggers`: EventTrigger and SentWebhookVerifier.

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `AbstractSentConnection`: secret credentials, connection configuration, and task cancellation.
- `SentClient`: HTTP requests, bounded retries, error redaction, and idempotency headers.
- `AbstractSentSingleTask`, `AbstractSentListTask`, and `SentFetchSupport`: shared response and pagination handling; list tasks default to internal storage.
- `messages.SendMessage`: sandbox/text/template sends and stable task-run idempotency keys.
- `triggers.EventTrigger` and `triggers.SentWebhookVerifier`: event filtering and timestamp-bounded HMAC verification compatible with the Kestra 1.3 webhook route.

### Project Structure

```
plugin-sent/
├── src/main/java/io/kestra/plugin/sent/
├── src/main/resources/{doc,icons,metadata}/
├── src/test/java/io/kestra/plugin/sent/
├── src/test/resources/
├── examples/
├── build.gradle
└── README.md
```

### Verification

- `./gradlew clean build spotlessCheck lintPluginDocs shadowJar`: unit/API tests, formatting, documentation, and packaging.
- `./gradlew webhookIntegrationTest -PkestraUrl=http://localhost:18083`: seven synthetic real-route tests against an isolated Kestra 1.3.39 server; startup instructions are in README.
- The `webhook-integration` CI job starts that server and runs the route tests separately from the default unit-test task. No Sent credentials or customer messages are used.

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
