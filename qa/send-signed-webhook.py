#!/usr/bin/env python3
"""Send a synthetic Sent-style signed webhook to a disposable Kestra QA flow."""

import argparse
import base64
import binascii
import getpass
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="Full Kestra webhook URL, including the URL key")
    parser.add_argument("--tamper", action="store_true", help="Change one body byte after signing; expect HTTP 401")
    args = parser.parse_args()

    signing_secret = getpass.getpass("Sent signing secret (whsec_...): ").strip()
    if not signing_secret.startswith("whsec_"):
        raise SystemExit("The signing secret must include the whsec_ prefix.")

    try:
        signing_key = base64.b64decode(signing_secret.removeprefix("whsec_"), validate=True)
    except (ValueError, binascii.Error) as error:
        raise SystemExit("The value after whsec_ is not valid base64.") from error

    timestamp = str(int(time.time()))
    webhook_id = str(uuid.uuid4())
    body = json.dumps(
        {
            "field": "message",
            "event": "message.received",
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "payload": {"message_id": "msg_disposable_qa", "status": "received"},
        },
        separators=(",", ":"),
    ).encode()
    signed = webhook_id.encode() + b"." + timestamp.encode() + b"." + body
    signature = "v1," + base64.b64encode(hmac.new(signing_key, signed, hashlib.sha256).digest()).decode()
    transmitted_body = body + b" " if args.tamper else body

    request = urllib.request.Request(
        args.url,
        data=transmitted_body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Webhook-ID": webhook_id,
            "X-Webhook-Timestamp": timestamp,
            "X-Webhook-Signature": signature,
        },
    )

    try:
        with urllib.request.urlopen(request) as response:
            print(f"HTTP {response.status}")
            if args.tamper:
                print("FAIL: a tampered body was accepted")
                return 1
            print("PASS: signed webhook accepted; verify the new Kestra execution is SUCCESS")
            return 0
    except urllib.error.HTTPError as error:
        print(f"HTTP {error.code}")
        if args.tamper and error.code == 401:
            print("PASS: tampered body rejected; verify no Kestra execution was created")
            return 0
        print("FAIL: webhook request was not accepted")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
