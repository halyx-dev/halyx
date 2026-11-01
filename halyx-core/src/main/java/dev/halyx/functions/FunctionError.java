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
package dev.halyx.functions;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * What a failed function reported itself, as the cause of the {@code CloudException} an invocation throws: Lambda's
 * {@code errorMessage} and {@code errorType}, or an HTTP function's status and body. The exception's own message
 * describes the failure with the function's name and only the start of its text; this keeps the text whole, so a
 * service can pass the function's reason on:
 *
 * <pre>{@code
 * try {
 *     resize.invoke(request);
 * } catch (CloudException e) {
 *     String reason = FunctionError.of(e).map(FunctionError::message).orElse(e.getMessage());
 * }
 * }</pre>
 *
 * It's never thrown on its own and has no stack trace. A memory-provider handler's exception is its cause.
 */
public final class FunctionError extends RuntimeException {

    private final byte[] body;
    private final Integer status;
    private final String type;

    /**
     * @param message the function's own text: Lambda's {@code errorMessage}, or the body as text
     * @param body    the error payload or response body
     * @param status  the HTTP status, or null for Lambda
     * @param type    Lambda's {@code errorType}, or null
     * @param cause   what the function threw, where it's known (the memory provider)
     */
    public FunctionError(String message, byte[] body, Integer status, String type, Throwable cause) {
        super(message, cause, false, false);
        this.body = body == null ? new byte[0] : body.clone();
        this.status = status;
        this.type = type;
    }

    /** An HTTP function's error: its status, and its body as both bytes and text. */
    public static FunctionError http(int status, byte[] body) {
        return new FunctionError(new String(body, StandardCharsets.UTF_8), body, status, null, null);
    }

    /** The {@code FunctionError} in {@code error}'s causes, if a function reported one. */
    public static Optional<FunctionError> of(Throwable error) {
        Set<Throwable> seen = new HashSet<>();
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof FunctionError reported) {
                return Optional.of(reported);
            }
        }
        return Optional.empty();
    }

    /** The function's own text, whole. */
    public String message() {
        return getMessage();
    }

    /** The error payload or response body, whole. */
    public byte[] body() {
        return body.clone();
    }

    /** The HTTP status; empty on Lambda. */
    public OptionalInt status() {
        return status == null ? OptionalInt.empty() : OptionalInt.of(status);
    }

    /** Lambda's {@code errorType}, or the memory provider's exception class; empty for HTTP functions. */
    public Optional<String> type() {
        return Optional.ofNullable(type);
    }
}
