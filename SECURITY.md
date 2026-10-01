# Security policy

Report suspected vulnerabilities privately to the future repository maintainers. Do not include live API keys, signing secrets, message bodies, phone numbers, or reusable signed webhook requests in an issue.

## Security boundaries

- API keys belong in Kestra secrets and are sent only as `x-api-key`.
- `profileId` is sent only as `x-profile-id` and is ignored for Sender Profile discovery calls.
- Non-loopback custom API origins must use HTTPS; redirects are disabled.
- Every mutation requires a validated idempotency key.
- Incoming events are verified on exact raw bytes before JSON parsing, in constant time, with replay-window enforcement.
- Neither webhook signatures nor raw payloads are logged.
- The emitted webhook `dedupeKey` does not itself deduplicate. Side-effecting flows must persist it atomically in a shared store.

## Secret rotation

1. Rotate the Sent API key or webhook signing secret in Sent.
2. Update the corresponding Kestra secret immediately.
3. For a signing-secret rotation, send a Sent test event and confirm the execution starts.
4. Revoke the old API credential where the Sent lifecycle permits it.
5. Review logs only for request IDs and status codes; never paste credentials into diagnostics.

The webhook URL `key` is a second secret independent of the Sent signing secret. Rotate it by changing the flow trigger and then updating the Sent endpoint URL.
