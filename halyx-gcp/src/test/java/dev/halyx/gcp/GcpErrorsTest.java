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
package dev.halyx.gcp;

import com.google.cloud.storage.StorageException;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.AlreadyExistsException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.PreconditionFailedException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.error.UnsupportedFeatureException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GcpErrorsTest {

    private static StorageException error(int status) {
        return new StorageException(status, "boom");
    }

    @Test
    void clientErrorsMapByStatus() {
        assertThat(GcpErrors.translate(error(400))).get().isInstanceOf(InvalidArgumentException.class);
        assertThat(GcpErrors.translate(error(416))).get().isInstanceOf(InvalidArgumentException.class);
        assertThat(GcpErrors.translate(error(401))).get().isInstanceOf(AccessDeniedException.class);
        assertThat(GcpErrors.translate(error(403))).get().isInstanceOf(AccessDeniedException.class);
        assertThat(GcpErrors.translate(error(404))).get().isInstanceOf(NotFoundException.class);
        assertThat(GcpErrors.translate(error(409))).get().isInstanceOf(AlreadyExistsException.class);
        assertThat(GcpErrors.translate(error(412))).get().isInstanceOf(PreconditionFailedException.class);
        assertThat(GcpErrors.translate(error(429))).get().isInstanceOf(ThrottledException.class);
        assertThat(GcpErrors.translate(error(501))).get().isInstanceOf(UnsupportedFeatureException.class);
        // Unlike Azure, a 503 is unavailable, not throttled; code 0 means there was no response at all.
        assertThat(GcpErrors.translate(error(503))).get().isInstanceOf(UnavailableException.class);
        assertThat(GcpErrors.translate(error(500))).get().isInstanceOf(UnavailableException.class);
        assertThat(GcpErrors.translate(error(0))).get().isInstanceOf(UnavailableException.class);
        // A status with no portable meaning is left to the driver's fallback.
        assertThat(GcpErrors.translate(error(418))).isEmpty();
    }

    @Test
    void networkFailures() {
        assertThat(GcpErrors.translate(new UncheckedIOException(new IOException("reset")))).get()
                .isInstanceOf(UnavailableException.class);
        assertThat(GcpErrors.translate(new IllegalStateException("other"))).isEmpty();
    }

    @Test
    void fromStatusMapsAsTheClientTranslationDoes() {
        assertThat(GcpErrors.fromStatus(400, "boom", null, "400", null)).isInstanceOf(InvalidArgumentException.class);
        assertThat(GcpErrors.fromStatus(416, "boom", null, "416", null)).isInstanceOf(InvalidArgumentException.class);
        assertThat(GcpErrors.fromStatus(401, "boom", null, "401", null)).isInstanceOf(AccessDeniedException.class);
        assertThat(GcpErrors.fromStatus(403, "boom", null, "403", null)).isInstanceOf(AccessDeniedException.class);
        assertThat(GcpErrors.fromStatus(404, "boom", null, "404", null)).isInstanceOf(NotFoundException.class)
                .hasMessage("boom").satisfies(e -> assertThat(e.providerErrorCode()).contains("404"));
        assertThat(GcpErrors.fromStatus(409, "boom", null, "409", null)).isInstanceOf(AlreadyExistsException.class);
        assertThat(GcpErrors.fromStatus(412, "boom", null, "412", null)).isInstanceOf(PreconditionFailedException.class);
        assertThat(GcpErrors.fromStatus(501, "boom", null, "501", null)).isInstanceOf(UnsupportedFeatureException.class);
        assertThat(GcpErrors.fromStatus(503, "boom", null, "503", null)).isInstanceOf(UnavailableException.class);
        assertThat(((ThrottledException) GcpErrors.fromStatus(429, "boom", null, "429", Duration.ofSeconds(3)))
                .retryAfter()).contains(Duration.ofSeconds(3));
        // Where the client translation leaves the error to the driver, fromStatus has to answer.
        assertThat(GcpErrors.fromStatus(418, "boom", null, "418", null)).isInstanceOf(UnknownCloudException.class);
    }
}
