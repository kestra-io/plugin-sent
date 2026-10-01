# Upgrade checklist

Use this checklist whenever bumping Kestra, Java, the Sent API version, or the documented OpenAPI snapshot.

- [ ] Read the current Kestra plugin developer guide and contribution rules.
- [ ] Compare the pinned Kestra BOM with the latest compatible release.
- [ ] Confirm Java source/target matches Kestra's current requirement.
- [ ] Download Sent's deployed OpenAPI document and record its SHA-256 digest.
- [ ] Diff authentication headers, base URL, paths, request fields, response envelopes, pagination, sandbox headers, error schemas, and idempotency behavior.
- [ ] Reconfirm `/v3/sender-profiles`; do not regress to deprecated `/v3/profiles`.
- [ ] Reconfirm the webhook HMAC construction, header names, signature prefix, and timestamp window.
- [ ] Run the official fixed webhook vector and tamper/replay tests.
- [ ] Run request-contract tests for every task, including profile-scope behavior.
- [ ] Run pagination tests for FETCH, FETCH_ONE, STORE, and NONE.
- [ ] Run secret-redaction and unsafe-origin tests.
- [ ] Run `spotlessCheck`, `check`, `lintPluginDocs`, and `shadowJar`.
- [ ] Install the shaded JAR into an actual local Kestra instance and verify catalogue visibility, schema rendering, a safe API task against WireMock, and a signed webhook execution.
- [ ] Update screenshots/evidence and this repository's compatibility matrix.
- [ ] Review dependency vulnerability findings and document dispositions.
