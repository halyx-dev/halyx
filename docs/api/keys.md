---
title: Keys and encryption
parent: API guide
nav_order: 13
---

# Keys and encryption: `KeyStore`, `CryptoKey`
{: .no_toc }

Encrypt, sign and MAC with keys held by a key service: **AWS KMS**, **Azure Key Vault**, **Google Cloud KMS** and
**HashiCorp Vault Transit**, plus in-memory keys for tests. The same code, the same errors and the same sealed format
on every cloud.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## How it works

The key services only fully agree on two things: wrapping a small data key, and signing a digest. Direct encryption
differs too much between them (AWS takes up to 4 KB, Azure only RSA-sized values), so Halyx never asks a key service
to encrypt your data:

- **Envelope encryption.** Each `encrypt` generates a fresh 256-bit data key, encrypts your data locally with
  AES-256-GCM, and asks the key service only to wrap the data key: one call, whatever the size of the data. The result
  is `SealedData` in Halyx's HLXS format, the same on every cloud.
- **Local verification.** `sign` hashes locally and has the key service sign the digest. `verify` checks the signature
  locally with the public key, which Halyx caches, so verifying makes no call.

Halyx never creates, rotates or deletes keys: infrastructure is yours. It uses keys that already exist.

## Key stores and keys

A `KeyStore` is a location of named keys, configured like a parameter store:

| Provider | Store location | A key's name |
|---|---|---|
| AWS KMS | none: the provider's account and region | an alias without `alias/`, so `orders-data` is `alias/orders-data` |
| Azure Key Vault | the vault URL, `https://acme.vault.azure.net` | the key's name |
| Google Cloud KMS | the key ring, `projects/acme/locations/global/keyRings/orders` | the crypto key's id |
| Vault Transit | the Transit mount, `transit` by default | the key's name |
| Memory | any string | the name the test created it under |

```java
KeyStore keys = halyx.provider("cloud").keyStore(KeyStoreConfig.builder()
        .location("https://acme.vault.azure.net")
        .prefix("orders-")
        .build());
CryptoKey data = keys.key("data");            // the key "orders-data"; no call yet
```

`keyStore()` with no configuration is the provider's default store: AWS's account and region, or Vault's `transit`
mount. Asking a context again with an equal configuration returns the same store, with its caches and statistics,
and asking a store again for a name with equal options returns the same key.

Names are portable: 1 to 63 letters, digits and hyphens, prefix included, which every key service accepts. To use the
provider's own names, such as a key ARN or a full Google Cloud crypto key name, set `nativeNames(true)`.

The first use of a key reads its metadata (`metadata()`, no cryptographic call) and checks it: a key whose type doesn't
fit what you ask of it throws `InvalidArgumentException`. Metadata is refreshed every few minutes, so a rotation is
seen without a restart.

## Encrypting

```java
byte[] aad = orderId.getBytes(StandardCharsets.UTF_8);   // bound to the data, checked when it's opened

SealedData sealed = data.encrypt(json, aad);              // one wrap call
byte[] stored = sealed.toBytes();                         // self-describing: store it anywhere

byte[] plain = data.decrypt(SealedData.parse(stored), aad);   // one unwrap call
```

The additional authenticated data (`aad`) isn't stored: you give it again to decrypt, and anything else fails with
`DataIntegrityException`. Use it to bind data to where it belongs, such as an order id, so a sealed value can't be
copied onto another record. It may be empty.

`SealedData.toText()` gives the same data as text (`hlxs1:` followed by base64url), for configuration values and
other text fields; `SealedData.parseText` reads it back.

`keys.decrypt(sealed, aad)` opens data sealed under any key of the store, taking the key from the sealed data itself:
readers don't need to know which key sealed it.

### Large data

`encryptingStream` and `decryptingStream` seal and open one segment at a time (64 KiB by default, set with
`segmentSize`), so data never has to fit in memory:

```java
try (OutputStream out = data.encryptingStream(Files.newOutputStream(path), aad)) {
    input.transferTo(out);                    // close() writes the final segment
}
try (InputStream in = keys.decryptingStream(Files.newInputStream(path), aad)) {
    in.transferTo(output);
}
```

Each segment is authenticated before its bytes are returned. Changed, reordered or truncated data fails with
`DataIntegrityException`, at the segment where it was changed or at the end for missing data, so a reader never sees
a silently shortened plaintext.

### Several keys, rotation and moving data

Sealed data can carry the data key wrapped by up to 16 keys, on any providers. Any one of them can open it:

```java
SealedData sealed = CryptoKeys.encrypt(json, aad, awsKey, azureKey);   // one wrap call per key
byte[] plain = azureKey.decrypt(sealed, aad);                           // or awsKey.decrypt
```

Only the header changes when keys are added, removed or replaced; the encrypted body is untouched, so even a large
object is cheap to move:

| Call | What it does |
|---|---|
| `key.rewrap(sealed)` | replaces the entry of this key's store with this key's current version: after a rotation, or to move between keys of one store |
| `key.rewrap(sealed, fromStore)` | replaces the entry `fromStore` can open with this key: to move data to another store or cloud |
| `key.addTo(sealed, fromStore)` | adds this key, so either can open the data |
| `sealed.withoutKey(keyId)` | removes a key's entry, with no key service call; the last one can't be removed |

On AWS and Vault, `rewrap` within one store happens inside the key service (`SERVER_SIDE_REWRAP`); elsewhere Halyx
unwraps and wraps, with the same result.

### Data keys for your own format

`generateDataKey()` returns a `DataKey`: the plaintext key and its wrapped form, to store next to data you encrypt
yourself. `decryptDataKey(wrapped)` unwraps it again, and `wrap(dataKey)` wraps it with another key. Close a
`DataKey` as soon as you can: closing zeroes the key held in it.

## Signing

```java
CryptoKey signing = keys.key("invoice-signing");
Signature signature = signing.sign(pdfBytes);          // hashed locally; the digest is signed by the key service
byte[] stored = signature.toBytes();                   // with its algorithm and key version

boolean valid = signing.verify(pdfBytes, Signature.parse(stored));   // local, with the cached public key
```

| `SigningAlgorithm` | Keys |
|---|---|
| `RSA_PSS_SHA256` | RSA, PSS with SHA-256 and a 32-byte salt |
| `RSA_PKCS1_SHA256` | RSA, PKCS #1 v1.5 with SHA-256 |
| `ECDSA_P256_SHA256` | EC on P-256 |
| `ECDSA_P384_SHA384` | EC on P-384 |

An EC key's algorithm follows from its curve. An RSA key can sign either way, so name the algorithm:

```java
CryptoKey rsa = keys.key("legacy-signing", KeyOptions.builder()
        .signingAlgorithm(SigningAlgorithm.RSA_PKCS1_SHA256)
        .build());
```

A signature records the key version that made it, so it still verifies after the key is rotated.
`signature.bytes()` are what the algorithm defines (DER for ECDSA), for formats such as JWS that need the raw
signature. `signDigest(digest)` signs a digest you computed yourself. `publicKey(version)`, `currentPublicKey()` and
`publicKeys()` give the public keys as `java.security.PublicKey`, for publishing in a JWK set.

## MACs

`mac(data)` and `verifyMac(data, mac)` compute and check an HMAC-SHA256 with a key that never leaves the key service:
on AWS KMS, Google Cloud KMS and Vault Transit (`KeyCapability.MAC`). Azure Key Vault offers it only on Managed HSM,
which Halyx doesn't support yet.

## Errors

The same on every cloud:

| Situation | Exception |
|---|---|
| The key doesn't exist | `NotFoundException` |
| No permission, or the key or its version is disabled, pending deletion or destroyed | `AccessDeniedException`, not retryable, with the cloud's code in `providerErrorCode()` |
| Wrong AAD, or changed, reordered or truncated sealed data | `DataIntegrityException` from decrypting; `false` from `verify` and `verifyMac` |
| Data sealed under other keys; an unknown format; a key whose type doesn't fit the purpose or algorithm; a name that isn't portable | `InvalidArgumentException` |
| The key service's request quotas | `ThrottledException`, retryable |

Every key operation can be repeated safely, so the [retry interceptor](errors.md) and the SDKs' retry modes apply.
Operations run through the interceptors under the service name `keys`. Plaintext, data keys and AAD never appear in
exceptions, logs, observations or `toString`.

## Performance and caches

Each `encrypt` makes one wrap call and each `decrypt` one unwrap call. Key service quotas are per account or project
and shared by everything in it, so two caches can cut calls. Both are off by default, because each trades some
security for cost:

```java
KeyStore keys = halyx.provider("cloud").keyStore(KeyStoreConfig.builder()
        .dataKeyReuse(Duration.ofMinutes(5), 10_000)     // encrypt: one data key for up to 5 minutes or 10,000 messages
        .dataKeyCache(Duration.ofMinutes(5), 1_000)      // decrypt: keep up to 1,000 unwrapped data keys for 5 minutes
        .build());
```

- **`dataKeyReuse`** seals many messages with one data key, so one wrapped copy covers more data. Each message still
  gets its own subkey and nonces.
- **`dataKeyCache`** keeps unwrapped data keys in memory, keyed by their wrapped form, so data sealed with one data key
  opens with one call. Keys are zeroed when they expire or are evicted.

Public keys are always cached, by version, since they aren't secret. `keys.statistics()` counts the calls that reached
the key service and the ones the caches saved.

## Testing with the memory provider

The memory provider holds real JDK keys, so sealed data and signatures are real. Create keys through
`InMemoryKeys`:

```java
KeyStore keys = halyx.provider("local").keyStore();
InMemoryKeys memory = keys.unwrap(InMemoryKeys.class);
memory.create("orders-data", KeyPurpose.ENCRYPTION);
memory.create("invoice-signing", KeyPurpose.SIGNING, SigningAlgorithm.ECDSA_P256_SHA256);
memory.create("tags", KeyPurpose.MAC);

memory.rotate("orders-data");     // a new current version; older ones still decrypt
memory.disable("orders-data");    // every use raises AccessDeniedException
memory.remove("orders-data");     // every use raises NotFoundException
```

It declares every capability.
