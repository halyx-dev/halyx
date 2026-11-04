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

"""An independent reader and writer of the HLXS v1 envelope format, from its specification (docs/api/keys-format.md).

It takes the data key directly, so it needs no key service: unwrapping the data key with AWS KMS, Azure Key Vault,
Google Cloud KMS or Vault Transit is the caller's job. It exists to show the specification is complete without
Halyx's Java code, and runs the published test vectors in CI.
"""

import hashlib
import hmac
import struct
from dataclasses import dataclass

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"HLXS"
FORMAT_VERSION = 1
ALGORITHM = 1
SALT_LENGTH = 16
NONCE_PREFIX_LENGTH = 7
COMMITMENT_LENGTH = 32
TAG_LENGTH = 16
DATA_KEY_LENGTH = 32
MAX_KEYS = 16
MAX_SEGMENT_SIZE = 16 * 1024 * 1024
TEXT_PREFIX = "hlxs1:"


class InvalidFormat(Exception):
    """Not readable HLXS v1: wrong magic, unknown version or algorithm, or a malformed key list."""


class IntegrityError(Exception):
    """Failed authentication: changed, reordered or truncated data, wrong AAD, or another data key."""


@dataclass(frozen=True)
class KeyEntry:
    key_id: str
    version: str
    wrap_algorithm: int
    wrapped: bytes


@dataclass(frozen=True)
class Header:
    segment_size: int
    salt: bytes
    nonce_prefix: bytes
    commitment: bytes
    keys: tuple
    length: int


def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    """HKDF with SHA-256 (RFC 5869), extract then expand."""
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    okm, block, counter = b"", b"", 1
    while len(okm) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        okm += block
        counter += 1
    return okm[:length]


def fixed_prefix(segment_size: int) -> bytes:
    return MAGIC + bytes([FORMAT_VERSION, ALGORITHM]) + struct.pack(">I", segment_size)


def commitment(data_key: bytes, salt: bytes, segment_size: int) -> bytes:
    return hkdf_sha256(data_key, salt, b"HLXS1 commit" + fixed_prefix(segment_size), COMMITMENT_LENGTH)


def subkey(data_key: bytes, salt: bytes, segment_size: int) -> bytes:
    return hkdf_sha256(data_key, salt, b"HLXS1 key" + fixed_prefix(segment_size), DATA_KEY_LENGTH)


def nonce(prefix: bytes, index: int, last: bool) -> bytes:
    return prefix + struct.pack(">IB", index, 1 if last else 0)


def segment_aad(aad: bytes) -> bytes:
    return struct.pack(">I", len(aad)) + aad


class _Reader:
    def __init__(self, data: bytes):
        self.data, self.offset = data, 0

    def take(self, n: int) -> bytes:
        if self.offset + n > len(self.data):
            raise IntegrityError("The HLXS header is truncated")
        value = self.data[self.offset:self.offset + n]
        self.offset += n
        return value

    def u8(self) -> int:
        return self.take(1)[0]

    def u16(self) -> int:
        return struct.unpack(">H", self.take(2))[0]

    def u32(self) -> int:
        return struct.unpack(">I", self.take(4))[0]

    def field(self) -> bytes:
        return self.take(self.u16())


def parse_header(sealed: bytes) -> Header:
    """Parses and checks the header's structure; the keys and tags are checked when the data is opened."""
    r = _Reader(sealed)
    if r.take(4) != MAGIC:
        raise InvalidFormat("Not HLXS sealed data")
    version = r.u8()
    if version != FORMAT_VERSION:
        raise InvalidFormat(f"Unknown HLXS format version {version}")
    algorithm = r.u8()
    if algorithm != ALGORITHM:
        raise InvalidFormat(f"Unknown HLXS algorithm {algorithm}")
    segment_size = r.u32()
    if not 0 < segment_size <= MAX_SEGMENT_SIZE:
        raise InvalidFormat(f"Segment size out of range: {segment_size}")
    salt = r.take(SALT_LENGTH)
    prefix = r.take(NONCE_PREFIX_LENGTH)
    stored_commitment = r.take(COMMITMENT_LENGTH)
    count = r.u8()
    keys = []
    for _ in range(count):
        key_id = r.field().decode("utf-8")
        key_version = r.field().decode("utf-8")
        wrap_algorithm = r.u8()
        wrapped = r.field()
        if ":" not in key_id or key_id.index(":") == 0 or not wrapped:
            raise InvalidFormat(f"Malformed key entry {key_id!r}")
        keys.append(KeyEntry(key_id, key_version, wrap_algorithm, wrapped))
    if not 1 <= len(keys) <= MAX_KEYS:
        raise InvalidFormat(f"HLXS data has 1 to {MAX_KEYS} key entries, not {len(keys)}")
    if len({k.key_id for k in keys}) != len(keys):
        raise InvalidFormat("A key id appears more than once")
    return Header(segment_size, salt, prefix, stored_commitment, tuple(keys), r.offset)


def open_sealed(sealed: bytes, data_key: bytes, aad: bytes) -> bytes:
    """The plaintext of HLXS sealed data, given its unwrapped data key and the AAD it was sealed with."""
    header = parse_header(sealed)
    if len(data_key) != DATA_KEY_LENGTH:
        raise IntegrityError("A data key is 32 bytes")
    # The commitment comes first: a wrong data key fails here, before anything is decrypted.
    if not hmac.compare_digest(commitment(data_key, header.salt, header.segment_size), header.commitment):
        raise IntegrityError("The key commitment doesn't match the data key")
    gcm = AESGCM(subkey(data_key, header.salt, header.segment_size))
    body = sealed[header.length:]
    if len(body) < TAG_LENGTH:
        raise IntegrityError("No final segment")
    full = header.segment_size + TAG_LENGTH
    count = (len(body) + full - 1) // full
    associated = segment_aad(aad)
    plaintext = bytearray()
    for i in range(count):
        segment = body[i * full:(i + 1) * full]
        if len(segment) < TAG_LENGTH:
            raise IntegrityError(f"Segment {i} is truncated")
        try:
            plaintext += gcm.decrypt(nonce(header.nonce_prefix, i, i == count - 1), segment, associated)
        except InvalidTag:
            raise IntegrityError(f"Segment {i} failed authentication") from None
    return bytes(plaintext)


def seal(data_key: bytes, keys, plaintext: bytes, aad: bytes, segment_size: int, salt: bytes,
         nonce_prefix: bytes) -> bytes:
    """Seals plaintext; the salt and nonce prefix are given, so test vectors can be reproduced exactly."""
    out = bytearray(fixed_prefix(segment_size))
    out += salt + nonce_prefix + commitment(data_key, salt, segment_size)
    out.append(len(keys))
    for key in keys:
        for value in (key.key_id.encode("utf-8"), key.version.encode("utf-8")):
            out += struct.pack(">H", len(value)) + value
        out.append(key.wrap_algorithm)
        out += struct.pack(">H", len(key.wrapped)) + key.wrapped
    gcm = AESGCM(subkey(data_key, salt, segment_size))
    count = max(1, -(-len(plaintext) // segment_size))
    for i in range(count):
        chunk = plaintext[i * segment_size:(i + 1) * segment_size]
        out += gcm.encrypt(nonce(nonce_prefix, i, i == count - 1), chunk, segment_aad(aad))
    return bytes(out)
