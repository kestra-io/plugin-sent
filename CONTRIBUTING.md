# Contributing

Changes should remain resource-specific, API-native, and compatible with the pinned Kestra/Sent contracts.

1. Read `AGENTS.md` and the [Kestra contribution guidelines](https://kestra.io/docs/plugin-developer-guide/contribution-guidelines).
2. Update or add a WireMock contract test for every behavioral change.
3. Use Sent sandbox mode for any explicitly authorized live-contract smoke test; never deliver a production message from CI.
4. Add `@Schema`, `@PluginProperty`, and a complete `@Example` for new public properties/components.
5. Run `./gradlew spotlessApply`, then `./gradlew clean spotlessCheck check lintPluginDocs shadowJar` on Java 25.
6. Update the compatibility matrix and `UPGRADE-CHECKLIST.md` evidence when changing Kestra or the Sent API contract.

Do not add a generic arbitrary-path/API-call task without an explicit security review. It would bypass the endpoint, method, authentication, sandbox, and idempotency guarantees provided by the typed components.
