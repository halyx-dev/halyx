/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.halyx.parameters;

import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SealedData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * How an encrypted view seals and opens values: each value is HLXS text sealed for the key {@code keys} names, with
 * the parameter's full name as AAD, and opened by {@code opener}, which finds the key from the sealed data. Listings
 * open {@value #PARALLEL_OPENS} values at a time.
 */
final class ParameterSealing {

    /** The text form of HLXS sealed data, as {@link SealedData#toText()} writes it. */
    static final String TEXT_PREFIX = "hlxs1:";
    /** At most this many key service calls at once for one listing. */
    static final int PARALLEL_OPENS = 16;

    private final Function<String, CryptoKey> keys;
    private final KeyStore opener;
    private final boolean allowPlaintext;
    private final String provider;
    private final ParameterKind kind;
    private final OptionalInt maxValueBytes;

    ParameterSealing(Function<String, CryptoKey> keys, KeyStore opener, boolean allowPlaintext, String provider,
                     ParameterKind kind, OptionalInt maxValueBytes) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.allowPlaintext = allowPlaintext;
        this.provider = provider;
        this.kind = kind;
        this.maxValueBytes = maxValueBytes;
    }

    ParameterSealing allowingPlaintext() {
        return allowPlaintext ? this : new ParameterSealing(keys, opener, true, provider, kind, maxValueBytes);
    }

    boolean allowsPlaintext() {
        return allowPlaintext;
    }

    private static byte[] aad(String fullName) {
        return fullName.getBytes(StandardCharsets.UTF_8);
    }

    /** The key for the parameter {@code name}, relative to the store's prefix. */
    private CryptoKey key(String name) {
        return Objects.requireNonNull(keys.apply(name), "the key for '" + name + "'");
    }

    /** {@code value} sealed as text, checked against the backend's size limit. */
    String seal(String name, String fullName, String value) {
        byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
        try {
            return checked(name, plaintext.length, key(name).encrypt(plaintext, aad(fullName)).toText());
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** {@link #seal} without waiting for the key service where the key can. */
    CompletableFuture<String> sealAsync(String name, String fullName, String value, Executor blocking) {
        byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
        int length = plaintext.length;
        return key(name).async(blocking).encrypt(plaintext, aad(fullName))
                .thenApply(sealed -> checked(name, length, sealed.toText()))
                .whenComplete((done, error) -> Arrays.fill(plaintext, (byte) 0));
    }

    private String checked(String name, int plaintextLength, String sealed) {
        if (maxValueBytes.isPresent() && sealed.length() > maxValueBytes.getAsInt()) {
            throw new InvalidArgumentException("Encrypted, " + noun() + " '" + name + "' is " + sealed.length()
                    + " bytes (" + plaintextLength + " of value and the rest the key's header); " + provider
                    + " stores at most " + maxValueBytes.getAsInt() + " bytes. Use a shorter value, a key with a "
                    + "shorter wrapped data key, or a store that takes longer values", null, provider, null);
        }
        return sealed;
    }

    private String noun() {
        return kind == ParameterKind.SECRETS ? "secret" : "parameter";
    }

    /** {@code parameter} opened, or as it is when it's plaintext and that's allowed. */
    Parameter open(String fullName, Parameter parameter) {
        Optional<SealedData> sealed = sealed(fullName, parameter);
        if (sealed.isEmpty()) {
            return parameter;
        }
        return opened(parameter, opener.decrypt(sealed.get(), aad(fullName)));
    }

    Optional<Parameter> open(String fullName, Optional<Parameter> parameter) {
        return parameter.map(p -> open(fullName, p));
    }

    /** {@link #open} without waiting for the key service where the store can. */
    CompletableFuture<Parameter> openAsync(String fullName, Parameter parameter, Executor blocking) {
        Optional<SealedData> sealed;
        try {
            sealed = sealed(fullName, parameter);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (sealed.isEmpty()) {
            return CompletableFuture.completedFuture(parameter);
        }
        return opener.async(blocking).decrypt(sealed.get(), aad(fullName)).thenApply(plain -> opened(parameter, plain));
    }

    CompletableFuture<Optional<Parameter>> openAsync(String fullName, Optional<Parameter> parameter, Executor blocking) {
        if (parameter.isEmpty()) {
            return CompletableFuture.completedFuture(parameter);
        }
        return openAsync(fullName, parameter.get(), blocking).thenApply(Optional::of);
    }

    /** Every value of {@code byFullName} opened, keeping its order and keys. */
    Map<String, Parameter> openAll(Map<String, Parameter> byFullName, Executor blocking) {
        try {
            return openAllAsync(byFullName, blocking).join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }
    }

    /**
     * Every value of {@code byFullName} opened, at most {@value #PARALLEL_OPENS} key service calls at a time; each call
     * starts on the thread that completed the one before, or the caller's.
     */
    CompletableFuture<Map<String, Parameter>> openAllAsync(Map<String, Parameter> byFullName, Executor blocking) {
        List<String> names = List.copyOf(byFullName.keySet());
        List<Parameter> opened = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            opened.add(null);
        }
        AtomicInteger next = new AtomicInteger();
        int workers = Math.min(PARALLEL_OPENS, names.size());
        CompletableFuture<?>[] running = new CompletableFuture<?>[workers];
        for (int w = 0; w < workers; w++) {
            running[w] = worker(names, byFullName, opened, next, blocking);
        }
        return CompletableFuture.allOf(running).thenApply(done -> {
            Map<String, Parameter> out = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                out.put(names.get(i), opened.get(i));
            }
            return out;
        });
    }

    private CompletableFuture<Void> worker(List<String> names, Map<String, Parameter> byFullName, List<Parameter> opened,
                                           AtomicInteger next, Executor blocking) {
        // A loop over values that need no key service call (plaintext), so they don't deepen the stack
        while (true) {
            int i = next.getAndIncrement();
            if (i >= names.size()) {
                return CompletableFuture.completedFuture(null);
            }
            String fullName = names.get(i);
            CompletableFuture<Parameter> call = openAsync(fullName, byFullName.get(fullName), blocking);
            if (call.isDone() && !call.isCompletedExceptionally()) {
                set(opened, i, call.join());
                continue;
            }
            return call.thenCompose(result -> {
                set(opened, i, result);
                return worker(names, byFullName, opened, next, blocking);
            });
        }
    }

    private static void set(List<Parameter> opened, int i, Parameter parameter) {
        synchronized (opened) {
            opened.set(i, parameter);
        }
    }

    /** The sealed data of a stored value; empty for plaintext that's allowed. */
    private Optional<SealedData> sealed(String fullName, Parameter parameter) {
        if (parameter.value().startsWith(TEXT_PREFIX)) {
            return Optional.of(SealedData.parseText(parameter.value()));
        }
        if (allowPlaintext) {
            return Optional.empty();
        }
        throw new InvalidArgumentException(Character.toUpperCase(noun().charAt(0)) + noun().substring(1) + " '"
                + fullName + "' isn't encrypted, but it's read through an encrypted view: it was written without the "
                + "key, or by another tool. Put it again through the view, or allow plaintext while moving to "
                + "encryption", null, provider, null);
    }

    private static Parameter opened(Parameter stored, byte[] plaintext) {
        try {
            return new Parameter(stored.name(), new String(plaintext, StandardCharsets.UTF_8), stored.version(),
                    stored.lastModified());
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
