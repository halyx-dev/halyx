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

# The FunctionConformanceTest function on Azure Functions, served at api/tck. A function that throws makes the host
# answer 500. The emulator tests and the tier 1 real-cloud run deploy this function app.

import json
import time

import azure.functions as func

counts = {}


def main(req: func.HttpRequest) -> func.HttpResponse:
    payload = req.get_json()
    action = payload.get("action")
    if action == "echo":
        body = json.dumps({"body": payload, "headers": dict(req.headers)})
        return func.HttpResponse(body, mimetype="application/json")
    if action == "fail":
        raise Exception(payload.get("message", "halyx-tck failure"))
    if action == "status":
        return func.HttpResponse("halyx-tck status %d" % payload["status"], status_code=payload["status"])
    if action == "sleep":
        if "id" in payload:
            counts[payload["id"]] = counts.get(payload["id"], 0) + 1
        time.sleep(payload["millis"] / 1000)
        return func.HttpResponse("{}", mimetype="application/json")
    if action == "count":
        counts[payload["id"]] = counts.get(payload["id"], 0) + 1
        if payload.get("fail"):
            raise Exception("halyx-tck counted failure")
        return func.HttpResponse("{}", mimetype="application/json")
    if action == "read":
        return func.HttpResponse(json.dumps({"count": counts.get(payload["id"], 0)}), mimetype="application/json")
    raise Exception("Unknown action: %s" % action)
