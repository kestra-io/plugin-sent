Use the Sent plugin to build messaging workflows on the v3 API without assembling raw HTTP calls.

## Configure authentication

Store API keys and webhook secrets in Kestra's secret backend.
Organization API keys may set `profileId` to scope supported calls through `x-profile-id`.
Profile API keys must leave it unset, and Sender Profile discovery remains organization-scoped.

## Test mutations safely

Start with `sandbox: true` for every mutation.
Every mutation requires an idempotency key. The default `sent_{{ taskrun.id }}` is stable for a single task run; deliberately re-sending the same task run replays Sent's cached response for up to 24 hours. Supply a new key for an intentional new mutation.
Use a stable business-event key when a retry may occur in another execution.
Never reuse the same key for different request content.

## Handle result size

List tasks default to `STORE`, which streams results to Kestra internal storage and returns a URI and count. Set `fetchType: FETCH` explicitly only for bounded lists that should be embedded in execution outputs.
Pagination is automatic and guarded by a configurable maximum page count.

## Receive events

The signed webhook trigger verifies HMAC-SHA256 and a positive timestamp window of at most one hour (five minutes by default) before parsing JSON. Use UTF-8 JSON requests. Kestra 1.3 exposes the HTTP body as a string: the plugin reconstructs UTF-8 bytes without JSON reserialization, preserving whitespace and non-ASCII text. It rejects non-UTF-8 charset declarations and replacement characters (U+FFFD), including legitimate replacement characters, to fail closed on lossy decoding.
Persistent cross-execution deduplication remains the flow author's responsibility.
Register Kestra's public HTTPS webhook URL in Sent and keep the URL key separate from the signing secret.

The URL is `https://<kestra-host>/api/v1/<tenant>/executions/webhook/<namespace>/<flow-id>/<key>`. Configure `signingSecret` from `secret('SENT_WEBHOOK_SIGNING_SECRET')` and `key` from a different secret. Accepted deliveries return 200; filtered events return 204; missing or malformed input returns 400; invalid or stale signatures return 401. As in Kestra 1.3's standard webhook service, failed input rendering or rejected execution conditions yield no execution (204).

See the component annotations for importable examples. No remote webhook is registered automatically. Treat payloads as potentially sensitive and avoid logging full bodies. Retries are bounded; killing a task cancels backoff within a 100 ms polling interval, while an in-flight HTTP request remains bounded by its configured timeout.
