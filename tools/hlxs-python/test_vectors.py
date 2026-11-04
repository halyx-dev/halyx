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

"""Runs the published HLXS v1 test vectors through the independent decoder: python -m unittest test_vectors"""

import json
import pathlib
import unittest

import hlxs

VECTORS = (pathlib.Path(__file__).resolve().parents[2]
           / "halyx-core" / "src" / "test" / "resources" / "hlxs" / "hlxs-v1-vectors.json")


class Rfc5869(unittest.TestCase):

    def test_hkdf_matches_rfc5869_test_case_1(self):
        okm = hlxs.hkdf_sha256(bytes.fromhex("0b" * 22), bytes.fromhex("000102030405060708090a0b0c"),
                               bytes.fromhex("f0f1f2f3f4f5f6f7f8f9"), 42)
        self.assertEqual(okm.hex(), "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                                    "34007208d5b887185865")


class Vectors(unittest.TestCase):

    def test_every_vector(self):
        document = json.loads(VECTORS.read_text(encoding="utf-8"))
        self.assertEqual(document["format"], "HLXS")
        self.assertEqual(document["formatVersion"], 1)
        self.assertGreaterEqual(len(document["vectors"]), 30)
        for vector in document["vectors"]:
            with self.subTest(vector["name"]):
                self.check(vector)

    def check(self, vector):
        data_key = bytes.fromhex(vector["dataKey"])
        aad = bytes.fromhex(vector["aad"])
        sealed = bytes.fromhex(vector["sealed"])
        expect = vector["expect"]
        if expect == "ok":
            plaintext = bytes.fromhex(vector["plaintext"])
            self.assertEqual(hlxs.open_sealed(sealed, data_key, aad), plaintext)
            if "inputs" in vector:
                inputs = vector["inputs"]
                keys = [hlxs.KeyEntry(k["keyId"], k["version"], k["wrapAlgorithm"], bytes.fromhex(k["wrapped"]))
                        for k in inputs["keys"]]
                resealed = hlxs.seal(data_key, keys, plaintext, aad, inputs["segmentSize"],
                                     bytes.fromhex(inputs["salt"]), bytes.fromhex(inputs["noncePrefix"]))
                self.assertEqual(resealed.hex(), vector["sealed"], "the writer reproduces the vector")
        elif expect == "invalid":
            with self.assertRaises(hlxs.InvalidFormat):
                hlxs.open_sealed(sealed, data_key, aad)
        elif expect == "integrity":
            with self.assertRaises(hlxs.IntegrityError):
                hlxs.open_sealed(sealed, data_key, aad)
        else:
            self.fail(f"Unknown expectation {expect}")


if __name__ == "__main__":
    unittest.main()
