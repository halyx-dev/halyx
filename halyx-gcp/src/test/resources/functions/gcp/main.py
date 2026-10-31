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

# The FunctionConformanceTest function on Cloud Run functions, with the Functions Framework, which answers 500 when
# the function throws. The emulator tests and the tier 1 real-cloud run deploy this file.

import json
import time

import functions_framework

counts = {}


@functions_framework.http
def tck(request):
    # The Functions Framework serves every path; in the emulator tests another path stands in for a function that
    # isn't deployed, which Cloud Run answers with 404.
    if request.path not in ("", "/"):
        return "Not found: %s" % request.path, 404
    payload = request.get_json(silent=True) or {}
    action = payload.get("action")
    if action == "echo":
        return json.dumps({"body": payload, "headers": dict(request.headers)}), 200, {"Content-Type": "application/json"}
    if action == "fail":
        raise Exception(payload.get("message", "halyx-tck failure"))
    if action == "status":
        return "halyx-tck status %d" % payload["status"], payload["status"]
    if action == "sleep":
        if "id" in payload:
            counts[payload["id"]] = counts.get(payload["id"], 0) + 1
        time.sleep(payload["millis"] / 1000)
        return "{}", 200, {"Content-Type": "application/json"}
    if action == "count":
        counts[payload["id"]] = counts.get(payload["id"], 0) + 1
        if payload.get("fail"):
            raise Exception("halyx-tck counted failure")
        return "{}", 200, {"Content-Type": "application/json"}
    if action == "read":
        return json.dumps({"count": counts.get(payload["id"], 0)}), 200, {"Content-Type": "application/json"}
    raise Exception("Unknown action: %s" % action)
