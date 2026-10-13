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
package dev.halyx.interceptor;

/**
 * Describes the operation an {@link Interceptor} is wrapping.
 *
 * @param service    the service family, such as {@code "storage"} or {@code "cache"}
 * @param operation  the operation name, such as {@code "put"} or {@code "get"}
 * @param provider   the provider id, such as {@code "aws"}
 * @param resource   the resource addressed, such as {@code "bucket/key"}
 * @param idempotent whether the operation can be safely replayed. Retry interceptors must respect
 *                   this; for example, a put from a one-shot {@code InputStream} is not replayable.
 */
public record OperationContext(String service, String operation, String provider, String resource,
                               boolean idempotent) {
}
