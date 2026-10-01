# Sent plugin — Kestra UI QA

Use only a disposable Sent sandbox account and disposable resource IDs. Do not enter a customer phone number, customer content, a production webhook, or a production API key. The task flow forces all three mutations to `sandbox: true`.

## What this kit tests

The QA is split into two importable flows because a webhook-triggered execution cannot provide the five manual resource inputs required by the task flow.

| Flow | Coverage |
| --- | --- |
| `sent_all_tasks_sandbox_qa` | All 13 Sent tasks in one execution; 10 read tasks plus 3 sandbox-only mutations. |
| `sent_webhook_trigger_qa` | The signed `EventTrigger`, event filtering, trigger outputs, and successful Kestra execution creation. |

The sandbox task responses are not reused for later reads because Sent may validate a sandbox mutation without persisting a contact or message. Existing disposable contact/message IDs are therefore explicit inputs. The local mock regression in `full-sent-local-qa.yaml` does reuse create/send outputs. See [the dated review results](REVIEW-RESULTS.md) for executed checks; mock success does not establish live-provider compatibility.

## Before importing

1. Install the built `plugin-sent-1.0.0-SNAPSHOT.jar` in every Kestra server and worker plugin directory, restart Kestra, then confirm **Sent** appears in the plugin catalogue with 13 tasks and 1 trigger.
2. Create these Kestra secrets through your normal Kestra secret backend:
   - `SENT_API_KEY`: an organization API key for a disposable Sent test account.
   - `SENT_WEBHOOK_URL_KEY`: a random URL key, different from the signing secret.
   - `SENT_WEBHOOK_SIGNING_SECRET`: the disposable Sent endpoint secret, including its `whsec_` prefix.
3. Collect five disposable values for the task form:
   - Sent-approved E.164 test phone number.
   - Existing disposable Contact ID.
   - Existing disposable Message ID with status/activity history.
   - Sender Profile ID readable by the organization key; the flow reuses it for `x-profile-id` scoping.
   - Existing disposable Template ID.
4. If you only have a profile API key, remove every `profileId` line from the task flow before saving. The profile list/get tasks require an organization key and cannot be validated with a profile-only key.

## Import and run all 13 tasks

1. In Kestra, open **Flows** and choose **Create**.
2. Open **Flow Code**, paste the complete contents of `qa/sent-all-tasks-sandbox-qa.yaml`, and click **Save**.
3. Confirm the topology contains 14 boxes: 13 Sent tasks followed by the `qa_summary` output task. The output task is not a Sent component.
4. Click **Execute**.
5. Fill the five input fields with disposable IDs and the approved test phone number, then start the execution.
6. Open the execution **Gantt** or **Topology** view. Every task from `get_account` through `get_template`, plus `qa_summary`, must be green and marked `SUCCESS`.
7. Open **Outputs**. The flow passes only if it shows:
   - `result`: `PASS — all 13 Sent tasks completed`
   - `tasksVerified`: `13`
   - `safety`: `sandbox only`
8. Open the outputs of `create_contact_sandbox`, `update_contact_sandbox`, and `send_message_sandbox`. Confirm each response reports sandbox behavior. Do not continue if any mutation indicates a real production write or delivery.
9. Inspect the remaining task outputs for a valid `row`, `rows`, `size`, request identifier, or provider data appropriate to that component. A flow-level `SUCCESS` with the PASS summary is the visual all-task acceptance gate.

## Import and test the signed trigger

1. Create another flow and paste `qa/sent-webhook-trigger-qa.yaml`, then click **Save**.
2. In Sent, create a temporary webhook for the disposable test account. Use this Kestra callback shape, substituting your public Kestra base URL, tenant, and the exact value stored in `SENT_WEBHOOK_URL_KEY`:

   `https://YOUR_KESTRA_HOST/api/v1/YOUR_TENANT/executions/webhook/qa.sent/sent_webhook_trigger_qa/YOUR_URL_KEY`

3. Store the endpoint signing secret from Sent as `SENT_WEBHOOK_SIGNING_SECRET`, including `whsec_`, then restart/reload Kestra if required by your secret backend.
4. Use Sent's webhook test function to send a disposable event. No customer message is required by this flow.
5. In Kestra, open **Executions**, select namespace `qa.sent`, and open the new `sent_webhook_trigger_qa` execution.
6. Confirm the execution source is the `sent_webhook` trigger, the execution and `verified_event_summary` task are `SUCCESS`, and **Outputs** shows the PASS result plus non-empty `event` and `dedupeKey` values.
7. Negative check: use an approved webhook test client to resend the exact body with one byte changed but the original signature. Kestra must return HTTP 401 and must not create an execution.
8. Delete only the temporary Sent webhook created for QA. Do not delete shared or production endpoints.

For a localhost or otherwise approved disposable endpoint, the included helper performs the positive and negative signature checks without putting the signing secret in shell history. Run it from the repository, paste the secret only at its hidden prompt, and use the exact callback URL from step 2:

```bash
python3 qa/send-signed-webhook.py --url 'https://YOUR_KESTRA_HOST/api/v1/YOUR_TENANT/executions/webhook/qa.sent/sent_webhook_trigger_qa/YOUR_URL_KEY'
python3 qa/send-signed-webhook.py --tamper --url 'https://YOUR_KESTRA_HOST/api/v1/YOUR_TENANT/executions/webhook/qa.sent/sent_webhook_trigger_qa/YOUR_URL_KEY'
```

The first command must print HTTP 200 and create one successful execution. The second must print HTTP 401 and create no execution.

## Pass/fail record

Record the Kestra version, plugin commit, task-flow execution ID, trigger-flow execution ID, Sent sandbox account identifier, and date. Mark the review passed only when all 13 task boxes and the signed-trigger execution are successful and no real delivery or customer data was used.

## Local mock regression

`full-sent-local-qa.yaml` is a separate synthetic regression, not a Sent sandbox test. It must be started by its signed webhook, not the UI Execute button: its summary reads `trigger.event` so a manual execution cannot incorrectly claim webhook verification.

Compile `qa/MockSent.java` using Java 25 and run `MockSent` in the same network namespace as the Kestra worker. It listens only on `127.0.0.1:9090`; if Kestra runs in Docker, the mock must run inside that disposable container too. Import the flow and run `send-signed-webhook.py` against its callback, using the public synthetic fixture secret already in the YAML. Run again with `--tamper`; expect HTTP 401 and no additional execution. Never use the fixture URL key or signing secret for a real endpoint.
