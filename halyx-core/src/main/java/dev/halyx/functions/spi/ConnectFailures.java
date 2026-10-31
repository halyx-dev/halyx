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
package dev.halyx.functions.spi;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;

/**
 * Recognises failures to connect, after which nothing was sent, so even a non-idempotent function can be called
 * again. Drivers use it in the retry conditions of their SDKs' clients.
 */
public final class ConnectFailures {

    private ConnectFailures() {
    }

    /** Whether {@code error}, or a cause of it, is a connection refused, a host not found or unreachable. */
    public static boolean isConnectFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            // Apache's and Netty's connect timeouts are both named so; Apache's isn't a ConnectException.
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException
                    || cause.getClass().getSimpleName().equals("ConnectTimeoutException")) {
                return true;
            }
        }
        return false;
    }
}
