# Submission review evidence

Recorded 2026-10-02 (Europe/Belgrade; local runtime execution occurred 2026-10-01 22:05 UTC).

## Scope and compatibility

The submission contains 13 Sent API tasks and one signed webhook trigger. It is based on official `kestra-io/plugin-sent` main commit `17a5a35b02ac84e21546ff3c392f3381d07b4af4`, replacing its placeholder task.

This implementation requires Kestra 2.0 / Java 25, unlike the scaffold's Kestra 1.3 / Java 21 baseline. The trigger uses raw request-body handling and asynchronous webhook execution introduced in the 2.0 contract. The platform version, CI Java input, Docker image, development container and README agree on that requirement. Maintainers should explicitly review this compatibility choice.

## Automated checks: PASS

Environment: Java 25, Gradle 9.8.0 (the version in the upstream wrapper), Kestra platform 2.0.0.

```bash
./gradlew clean build lintPluginDocs shadowJar dependencies --configuration runtimeClasspath --warning-mode all
```

The recorded run used the extracted official Gradle 9.8.0 distribution directly because wrapper downloading was slow. Its SHA-256 matched the official checksum: `bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c`.

- 49 tests passed; 0 failed; 0 skipped.
- All 25 plugin documentation lint rules passed.
- Spotless verification, compilation, build and shaded JAR packaging passed.
- JAR manifest contains the Sent name, group, description and version metadata; packaged resources include icons, documentation and plugin service registration.
- Added trigger integration tests cover accepted signatures, raw UTF-8 bytes, tampering, expired signatures, event filtering, missing signature headers and a signed JSON `null` body. The last case now rejects with HTTP 400 without creating an execution.
- The build reports a Gradle 10 deprecation for `StartParameter.newInstance()` in the build-tool/plugin stack. It does not fail Gradle 9.8.0; it is not a clean Gradle 10 compatibility claim.

## Packaged local runtime: PASS (mock, not live Sent)

The plugin JAR was loaded into a fresh `kestra/kestra:v2.0.0` container with local authentication and an in-memory database. Only the main UI/API port was exposed, bound to localhost. `MockSent` ran inside the same container. No real Sent credentials, customer data or external messages were used.

Flow: `qa.sent.sent_all_components_qa`.

Execution: `3xw0AMpJZsJrmkAODWFkZ2`, source `sent_webhook`, state `SUCCESS`.

The execution API reported `SUCCESS` for every component:

- GetAccount
- CreateContact, UpdateContact, GetContact, ListContacts
- SendMessage, GetMessageStatus, GetMessageActivities
- GetPhoneNumberDetails
- GetSenderProfile, ListSenderProfiles
- GetTemplate, ListTemplates
- Final summary task (not a Sent component)

CreateContact and SendMessage outputs were reused by downstream reads. The signed webhook returned HTTP 200 and created the execution. A body changed after signing returned HTTP 401; the execution count remained one. The final summary requires trigger data and cannot claim webhook success from a manual execution.

Both `sent-all-tasks-sandbox-qa.yaml` and `sent-webhook-trigger-qa.yaml` passed the live Kestra flow validation endpoint with empty warnings and deprecation lists. The sandbox flows themselves have not been executed against the real Sent API in this review run. Runtime evidence above was checked through Kestra's API; a new UI screenshot was not captured.

## Security/dependency review: limited checks completed

- The plugin uses Kestra's provided HTTP client and JSON mapper; no third-party runtime library classes are bundled in its shaded JAR. The runtime dependency report resolves the platform/BOM constraints.
- A focused source pattern scan found no private keys, common GitHub/Slack credential prefixes, user-machine paths or stale build-plan references. Public synthetic secrets remain only in test/local-mock fixtures by design. This is not a full secret-scanner certification.
- No database files, build outputs, local runtime configuration, real provider credentials or historical QA execution dumps were copied into the submission.
- No comprehensive dependency vulnerability scanner or independent penetration test was run. Framework/deployment vulnerabilities and test/build dependencies still require the maintainers' normal security checks.

## Still needed before release

1. Run both documented UI QA flows with an approved disposable Sent sandbox account, organization API key and test profile/contact/message/template IDs. Confirm sandbox mutation responses and no delivery.
2. Receive a provider-originated signed test event through a disposable public HTTPS callback, then run the negative signature test. Local synthetic signing is not a substitute for this check.
3. Obtain Kestra maintainer approval for the 2.0 / Java 25 baseline and pass upstream CI/review.
4. Have Kestra merge and release the plugin. A prepared branch or PR does not itself make the integration available in the catalogue.

These live-provider checks were blocked by unavailable approved credentials/resources, not reported as passed. Review can proceed with these limitations explicit; production/release readiness is not asserted.
