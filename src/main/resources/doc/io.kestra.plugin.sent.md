Use the Sent plugin to build messaging workflows on the v3 API without assembling raw HTTP calls.

## Configure authentication

Store API keys and webhook secrets in Kestra's secret backend.
Organization API keys may set `profileId` to scope supported calls through `x-profile-id`.
Profile API keys must leave it unset, and Sender Profile discovery remains organization-scoped.

## Test mutations safely

Start with `sandbox: true` for every mutation.
Every mutation requires an idempotency key, and the default is stable for a single task run.
Use a stable business-event key when a retry may occur in another execution.
Never reuse the same key for different request content.

## Handle result size

List tasks support Kestra `FetchType`; choose `STORE` for large data sets.
Pagination is automatic and guarded by a configurable maximum page count.

## Receive events

The signed webhook trigger verifies exact raw bytes and the timestamp window before parsing.
Persistent cross-execution deduplication remains the flow author's responsibility.
Register Kestra's public HTTPS webhook URL in Sent and keep the URL key separate from the signing secret.

See the repository README for installation, examples, webhook registration, data-handling guidance, and the complete reliability model.
