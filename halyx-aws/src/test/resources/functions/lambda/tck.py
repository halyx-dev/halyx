#
# Copyright 2026 Kamran Zafar
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# The FunctionConformanceTest function on Lambda: headers arrive as the ClientContext's custom map. The emulator
# tests and the tier 1 real-cloud run deploy this file. Counts are kept in the DynamoDB table COUNTS_TABLE where it
# is set, as Lambda may run invocations on several instances; in memory otherwise, with a concurrency of one.

import os
import time

TABLE = None
if os.environ.get("COUNTS_TABLE"):
    import boto3

    TABLE = boto3.resource("dynamodb").Table(os.environ["COUNTS_TABLE"])

counts = {}


def add_run(run_id):
    if TABLE is not None:
        TABLE.update_item(Key={"id": run_id}, UpdateExpression="ADD runs :one", ExpressionAttributeValues={":one": 1})
    else:
        counts[run_id] = counts.get(run_id, 0) + 1


def runs(run_id):
    if TABLE is not None:
        item = TABLE.get_item(Key={"id": run_id}, ConsistentRead=True).get("Item")
        return int(item["runs"]) if item else 0
    return counts.get(run_id, 0)


def handler(event, context):
    action = event.get("action")
    if action == "echo":
        client_context = getattr(context, "client_context", None)
        headers = dict(client_context.custom or {}) if client_context is not None else {}
        return {"body": event, "headers": headers}
    if action == "fail":
        raise Exception(event.get("message", "halyx-tck failure"))
    if action == "sleep":
        if "id" in event:
            add_run(event["id"])
        time.sleep(event["millis"] / 1000)
        return {}
    if action == "count":
        add_run(event["id"])
        if event.get("fail"):
            raise Exception("halyx-tck counted failure")
        return {}
    if action == "read":
        return {"count": runs(event["id"])}
    raise Exception("Unknown action: %s" % action)
