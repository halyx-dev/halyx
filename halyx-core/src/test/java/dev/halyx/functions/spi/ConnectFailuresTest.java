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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectFailuresTest {

    /** Apache's connect timeout, which isn't a ConnectException. */
    private static final class ConnectTimeoutException extends InterruptedIOException {
    }

    @Test
    void failuresToConnectAreRecognisedThroughTheirCauses() {
        assertThat(ConnectFailures.isConnectFailure(new ConnectException("Connection refused"))).isTrue();
        assertThat(ConnectFailures.isConnectFailure(new UncheckedIOException(new UnknownHostException("nowhere")))).isTrue();
        assertThat(ConnectFailures.isConnectFailure(new RuntimeException(new NoRouteToHostException()))).isTrue();
        assertThat(ConnectFailures.isConnectFailure(new RuntimeException(new ConnectTimeoutException()))).isTrue();
    }

    @Test
    void failuresAfterConnectingAreNot() {
        assertThat(ConnectFailures.isConnectFailure(new SocketTimeoutException("Read timed out"))).isFalse();
        assertThat(ConnectFailures.isConnectFailure(new IOException("Connection reset"))).isFalse();
        assertThat(ConnectFailures.isConnectFailure(new IllegalStateException())).isFalse();
    }
}
