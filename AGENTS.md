# Kestra Sent Plugin

Native Sent v3 tasks for Kestra 2.0 and Java 25. Public components live in the account, contacts, messages, numbers, profiles, templates, and triggers subpackages.

## Local rules

- Use Kestra's internal HTTP client and Jackson/FileSerde serializers.
- Never log credentials, phone numbers, message content, signatures, or webhook bodies.
- Require idempotency for mutations; examples and QA use sandbox mode.
- Verify signatures over the exact webhook bytes before JSON parsing.
- Keep all 13 tasks and the signed EventTrigger covered by unconditional tests.
- Keep docs and metadata consistent with the implemented components.

Run `./gradlew clean spotlessCheck check lintPluginDocs shadowJar` on Java 25 before submitting changes.

See https://kestra.io/docs/plugin-developer-guide/contribution-guidelines.
