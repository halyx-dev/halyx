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

import dev.halyx.capability.Capability;

/** Function invocation features that differ between providers. */
public enum FunctionCapability implements Capability {
    /**
     * {@link FunctionInvoker#trigger}: an asynchronous, fire-and-forget invocation (Lambda's
     * {@code InvocationType=Event}). Portable code starts functions with events or messaging instead.
     */
    TRIGGER,
    /**
     * Headers on a {@link FunctionInvoker#trigger}. Lambda passes headers in a {@code ClientContext}, which only
     * synchronous invocations take, so it doesn't declare this.
     */
    TRIGGER_HEADERS
}
