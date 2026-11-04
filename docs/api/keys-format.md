---
title: HLXS sealed data format
parent: API guide
nav_order: 14
---

# The HLXS v1 sealed data format
{: .no_toc }

HLXS is a compact binary envelope for data encrypted with AES-256-GCM under a data key, where the data key is
wrapped by one or more keys held in key services (AWS KMS, Azure Key Vault, Google Cloud KMS, HashiCorp Vault
Transit). Sealed data is self-describing, so any implementation, in any language, on any cloud, can open it with one
of those keys. Halyx writes and reads it through [`CryptoKey` and `KeyStore`](keys.md); this page specifies the bytes,
independently of Halyx.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Status and licence

- **Version:** HLXS version 1. **Status:** draft, until it's published with Halyx 1.2.0. After that, version 1 is
  stable: see [Stability](#stability).
- **Licence:** this specification and its test vectors are offered under the
  [Open Web Foundation Final Specification Agreement 1.0 (OWFa 1.0)](https://www.openwebfoundation.org/the-agreements/the-owf-1-0-agreements-granted-claims/owfa-1-0),
  which grants a copyright licence to the specification and a royalty-free patent commitment for implementations. Its
  author, Kamran Zafar, will sign the OWFa 1.0 for HLXS version 1 when the specification is published with Halyx
  1.2.0.
- **Contributions** to this specification or its test vectors are made under the
  [OWF Contributor License Agreement 1.0 - Copyright and Patent](https://www.openwebfoundation.org/the-agreements/the-owf-1-0-agreements-granted-claims/owf-contributor-license-agreement-1-0-copyright-and-patent).
  Code that implements it, Halyx and the Python decoder included, is licensed under Apache-2.0.

## Conventions

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are to be read as in RFC 2119 and RFC 8174 when, and only
when, they appear in capitals.

All integers are unsigned and big-endian. `‖` is concatenation. A *field* is a `u16` length followed by that many
bytes. Strings are UTF-8. *Data key* means the 32-byte AES key that encrypts one message's body.

## Layout

```
offset  size        field
0       4           magic             "HLXS" (48 4c 58 53)          ┐
4       1           format version    1                             │ the fixed prefix:
5       1           algorithm         1                             │ never changes for
6       4           segment size      u32, plaintext bytes per      │ the envelope's life
                                      full segment                  ┘
10      16          salt              random, unique per message
26      7           nonce prefix      random
33      32          key commitment    see Key commitment
65      1           key count         1 to 16
66      ...         key entries       key count × entry
...     ...         segments          the encrypted body
```

Each **key entry** is:

```
field   key id           UTF-8, "<scheme>:<provider id>" (see Key ids)
field   key version      UTF-8; empty when the key service reports no version
u8      wrap algorithm   how the data key was wrapped (see Registries)
field   wrapped key      the wrapped data key, 1 to 65535 bytes
```

The **body** is the plaintext split into segments of `segment size` bytes, the last of which may be shorter, or
empty for an empty plaintext. Each segment is stored as its AES-GCM ciphertext followed by its 16-byte tag, so every
segment but the last is `segment size + 16` bytes. There is always at least one segment.

## Algorithm 1: AES-256-GCM in segments

### Derivation

Two values are derived from the data key `K`, the salt and the fixed prefix (the first 10 bytes), with HKDF-SHA256
(RFC 5869):

```
commitment = HKDF-SHA256(IKM = K, salt = salt, info = "HLXS1 commit" ‖ fixed prefix, L = 32)
subkey     = HKDF-SHA256(IKM = K, salt = salt, info = "HLXS1 key"    ‖ fixed prefix, L = 32)
```

The info strings are ASCII. `subkey` is the AES-256 key for every segment of this message.

The key entries are deliberately not inputs: rewrapping the data key, or adding or removing a key, rewrites the
header and leaves the body unchanged. The key commitment protects the entries instead.

### Segments

Segment `i` (counting from 0) is sealed with AES-256-GCM under `subkey`, with a 128-bit tag and:

```
nonce = nonce prefix (7 bytes) ‖ u32 i ‖ u8 last      last = 1 for the final segment, else 0
AAD   = u32 length of the caller's AAD ‖ caller's AAD
```

The caller's additional authenticated data isn't stored: a reader supplies it again. It may be empty.

The segment number in the nonce means segments can't be reordered; the last flag means the data can't be truncated
at a segment boundary or extended.

## Key commitment

AES-GCM doesn't bind a ciphertext to a single key, so without more an envelope could be built to open to different
plaintexts under different data keys. HLXS stores a key commitment derived from the data key (see
[Derivation](#derivation)). A reader MUST recompute it from the data key it unwrapped, and MUST compare it with the
stored value in constant time, **before** decrypting any segment. A mismatch is an integrity error.

The commitment is also what protects the key entries: a replaced or forged entry unwraps to a different data key, and
fails the check.

## Reading

A reader MUST perform these steps in order:

1. **Parse the header.** It's a format error if:
   - the magic isn't `HLXS`
   - the format version isn't 1, or the algorithm isn't 1
   - the segment size is 0 or above 16 MiB (16,777,216)
   - the key count is 0 or above 16
   - two entries have the same key id
   - an entry's key id has no `:` after a non-empty scheme, or its wrapped key is empty

   Data that ends inside the header is an integrity error.
2. **Choose an entry.** Use the first entry the reader can open: one whose scheme and wrap algorithm it implements,
   for a key it has access to. Entries with an unknown scheme or wrap algorithm MUST be skipped, not rejected. With
   no usable entry, the data can't be opened; that is a format error, not an integrity error.
3. **Unwrap the data key** as the entry's [wrapping profile](#wrapping-profiles) says. A result that isn't 32 bytes is
   an integrity error.
4. **Check the key commitment**, as in [Key commitment](#key-commitment).
5. **Derive the subkey and open the segments** in order. A body shorter than 16 bytes, a segment shorter than 16
   bytes, or any failed tag is an integrity error.

A reader that streams the plaintext MUST NOT release a segment's bytes before its tag is verified. It MUST report an
integrity error, rather than a normal end of data, when the data ends without a segment marked last. Plaintext
released before the end SHOULD be treated as untrusted until the final segment has been verified.

### Error classes

Implementations SHOULD keep two error classes apart, since callers handle them differently:

| Class | Meaning | In Halyx |
|---|---|---|
| Format error | not readable HLXS v1, or no entry this reader can use | `InvalidArgumentException` |
| Integrity error | changed, reordered or truncated data; wrong AAD; another data key | `DataIntegrityException` |

## Writing

A writer:

- MUST use a 32-byte data key from a cryptographically secure random generator. It SHOULD use a fresh data key for
  each message. It MAY reuse one for at most 2^32 messages, since each message's salt gives it its own subkey and
  nonces.
- MUST use a fresh random 16-byte salt for every message, and a random 7-byte nonce prefix.
- MUST use a segment size from 4 KiB (4,096) to 16 MiB. The RECOMMENDED size is 64 KiB (65,536). Readers accept
  any size from 1 byte to 16 MiB, which the test vectors use.
- MUST NOT write more than 2^32 segments.
- MUST write 1 to 16 key entries with distinct key ids, every one wrapping the same data key.
- MUST mark exactly the final segment as last. An empty plaintext is one final segment with no ciphertext bytes.
- MUST write the commitment for the data key it used.

## Key ids

A key id is `<scheme>:<provider id>`. The scheme says which wrapping profile applies; the provider id is the key's
canonical id in its key service, the one that stays valid when an alias or display name changes. Readers use it to
find the key; it isn't secret, but it does reveal the key's name and, on some services, its account or project.

| Scheme | Provider id | Example |
|---|---|---|
| `aws-kms` | the key ARN | `aws-kms:arn:aws:kms:eu-west-2:111122223333:key/1234abcd-12ab-34cd-56ef-1234567890ab` |
| `azure-kv` | the vault URL, `/keys/` and the key name, without a version | `azure-kv:https://acme.vault.azure.net/keys/orders` |
| `gcp-kms` | the full crypto key name, without a version | `gcp-kms:projects/acme/locations/global/keyRings/orders/cryptoKeys/data` |
| `vault-transit` | the Transit mount, `/` and the key name; the Vault address is configuration, not part of the id | `vault-transit:transit/orders` |
| `memory` | an implementation's test keys; not interoperable | `memory:ring/data` |

## Wrapping profiles

Each wrap algorithm defines exactly how a data key is wrapped and unwrapped, so that independent implementations
interoperate.

**1, `aws-kms` (AWS KMS, symmetric key).**
- Wrap: `Encrypt` with `KeyId` = the key ARN, alias or key id; `Plaintext` = the data key; no `EncryptionContext`;
  `EncryptionAlgorithm` = `SYMMETRIC_DEFAULT`. The entry's key id is `aws-kms:` and the `KeyId` in the response (the
  ARN). The key version is empty. The wrapped key is the `CiphertextBlob`.
- Unwrap: `Decrypt` with `CiphertextBlob` = the wrapped key and `KeyId` = the ARN from the key id.

**2, `azure-kv` with `RSA-OAEP-256` (Azure Key Vault, RSA key of 2048 bits or more).**
- Wrap: `wrapKey` with `alg` = `RSA-OAEP-256` and `value` = the data key, on the key's specific version
  (`{vault}/keys/{name}/{version}/wrapkey`). The key version is that version id, and MUST NOT be empty.
- Unwrap: `unwrapKey` with `alg` = `RSA-OAEP-256` on the same version.
- The vault in the key id is the vault the key was used through. Implementations SHOULD build the request URL from
  their configured vault and the key's name and version, not from a `kid` URL returned by the service.

**3, `azure-kv` with `A256KW` (Azure Key Vault Managed HSM, AES key).** Reserved. Not yet specified.

**4, `gcp-kms` (Google Cloud KMS, `ENCRYPT_DECRYPT` key).**
- Wrap: `Encrypt` with `name` = the crypto key (its primary version is used) or a specific version; `plaintext` = the
  data key; no `additionalAuthenticatedData`. The key version is the version number from the response's `name`.
- Unwrap: `Decrypt` with `name` = the crypto key, without a version, and `ciphertext` = the wrapped key.
- Implementations SHOULD send and check the CRC32C fields (`plaintextCrc32c`, `ciphertextCrc32c`, and the
  `verified...` flags) as Google recommends. A mismatch is an integrity error.

**5, `vault-transit` (HashiCorp Vault Transit, `aes256-gcm96` or another symmetric key type).**
- Wrap: `POST {mount}/encrypt/{name}` with `plaintext` = the base64 of the data key, and no `context`. The wrapped
  key is the returned `ciphertext` string (`vault:v<N>:...`) as UTF-8 bytes. The key version is `N`.
- Unwrap: `POST {mount}/decrypt/{name}` with `ciphertext` = the wrapped key as a string, then base64-decode the
  `plaintext`.

**6, `memory` (test keys).** AES-256-GCM with a 12-byte random nonce, the key id's UTF-8 bytes as AAD, stored as
`nonce ‖ ciphertext ‖ tag`. For tests and local development only. Its keys never leave one process, so it can't
interoperate.

## Registries

**Wrap algorithms** (the entry's `u8`):

| Id | Algorithm |
|---|---|
| 1 | `aws-kms` symmetric |
| 2 | `azure-kv` `RSA-OAEP-256` |
| 3 | `azure-kv` `A256KW` (reserved) |
| 4 | `gcp-kms` symmetric |
| 5 | `vault-transit` |
| 6 | `memory` |
| 7 to 239 | unassigned |
| 240 to 255 | private use: never assigned, never written by Halyx |

**Key id schemes:** the five above. Scheme names are lower-case letters, digits and hyphens. Schemes starting with
`x-` are for private use and are never assigned.

**Policy:** a new wrap algorithm or scheme is assigned when a published specification of its wrapping profile, in the
style above, is accepted into this document, by pull request to the Halyx repository. The maintainer keeps the
registry for now; the policy is written so it can move to a neutral home. Assigned entries are never reused or
changed. Because readers skip unknown entries, new ones never break existing readers.

## Text encoding

Where sealed data must be text, such as a configuration value, it is written as `hlxs1:` followed by the unpadded
base64url (RFC 4648 section 5) of the bytes. This encoding is optional and separate from the binary format.

## Stability

Version 1's layout, derivations, nonce and AAD construction, and registry assignments never change once published.
New wrap algorithms and schemes are only added. Any other change is a new format version, with its own number, and
readers keep reading version 1.

## Security considerations

- **Confidentiality and integrity** rest on AES-256-GCM, a fresh subkey per message, and nonces that are unique within
  a message by construction. Because every message has its own salt, and so its own subkey, reusing a data key never
  repeats a (key, nonce) pair.
- **The AAD** binds data to its context, such as a record id. Use it: otherwise sealed data can be copied between
  records and still opens.
- **The header isn't secret.** Key ids name keys, and on some services an account or project. The lengths of the
  plaintext and of each key entry are visible.
- **Key entries can be removed** without detection; that is how a key is retired. Removing every entry but one leaves
  the data readable by that key alone.
- **Data key reuse** spreads one wrapped key over more data, so a single unwrapped data key opens more messages.
  Writers SHOULD limit it by age and count.
- **Not provided:** proof of who sealed the data. Anyone who can wrap with one of the keys can seal data for it. Use a
  signature for origin.

## Test vectors

[`hlxs-v1-vectors.json`](https://github.com/halyx-dev/halyx/blob/main/halyx-core/src/test/resources/hlxs/hlxs-v1-vectors.json)
holds 30 vectors, under the same licence as this specification. Each gives the data key directly, so no key service
is needed, and its expectation:
- `ok`: opens to `plaintext`. With `inputs`, a writer given the same inputs MUST produce `sealed` byte for byte.
- `invalid`: a format error.
- `integrity`: an integrity error.

They cover one, two and sixteen keys; one segment, several, an exact multiple and an empty plaintext; empty AAD; the
default segment size; a rewritten key list over an unchanged body; entries with unknown algorithms and schemes; and
tampering with every header field, the body and the tags, plus truncation, reordering and extension.

Two implementations run them: Halyx's Java code (`HlxsVectorsTest`), and an independent Python decoder written from
this page alone ([`tools/hlxs-python`](https://github.com/halyx-dev/halyx/tree/main/tools/hlxs-python)).

## Example

The vector `two-keys-two-segments` seals `{"orderId":"o-1001","email":"ana@example.com"}` (46 bytes) with AAD
`orders/o-1001`, for an AWS KMS key and an Azure Key Vault key, in 32-byte segments. Its 402 bytes:

```
0000  484c5853                  magic
0004  01                        format version
0005  01                        algorithm
0006  00000020                  segment size 32
000a  a0a1…aeaf                 salt
001a  c0c1c2c3c4c5c6            nonce prefix
0021  1b027641…d7c91cb2         key commitment
0041  02                        key count
0042  0053 6177732d…            key id "aws-kms:arn:aws:kms:eu-west-2:…"
0097  0000                      key version: none
0099  01                        wrap algorithm 1 (aws-kms)
009a  0030 5051…7e7f            wrapped key (placeholder)
00cc  0031 617a7572…            key id "azure-kv:https://acme.vault.azure.net/keys/orders"
00ff  0020 3366…3838            key version "3f2a9c41d0e14b6f8a7e5d2c1b0a9f88"
0121  02                        wrap algorithm 2 (azure-kv RSA-OAEP-256)
0122  0020 1011…2e2f            wrapped key (placeholder)
0144  ebf45e53…  9dbe3cdb…      segment 0: 32 bytes ciphertext, 16 bytes tag
0174  20d952f6…  ac4d98dd…      segment 1 (last): 14 bytes ciphertext, 16 bytes tag
```

## Rationale and comparison

*This section is informative.*

Several formats already wrap data keys, so here is why HLXS exists, and what it borrowed:

- **The AWS Encryption SDK message format** has segments ("frames"), several encrypted data keys and, since version 2,
  key commitment; HLXS takes all three ideas from it. But its default algorithm suite signs every message with ECDSA,
  its key providers are centred on AWS, and it has no official Azure or Google Cloud support.
- **Tink** (`KmsEnvelopeAead`, streaming AEAD) is widely reviewed, with AWS and Google Cloud integrations. But it has no
  official Azure integration, its envelope holds one wrapped key, and using it means depending on Tink's libraries.
- **JWE and COSE** are standards with several recipients each, and KMS-wrapped keys fit their recipient model. But
  neither has segments, so a large object must be held whole to be verified, and JWE's text form adds a third to its
  size.
- **age** is a small, well-specified file format with several recipients, segments and several implementations; it
  is the closest in spirit. But its recipients are public keys and passphrases rather than cloud key services, and it
  has no AAD.

HLXS combines what a library working across clouds needs: wrapping profiles for each key service, written down
exactly; segments for large objects, with random access and parallel reads; key commitment; several keys on different
clouds in one envelope; caller AAD; and a compact binary form with a text form for configuration values.
