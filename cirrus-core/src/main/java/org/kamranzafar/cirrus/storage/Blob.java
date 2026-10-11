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
package org.kamranzafar.cirrus.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A downloaded blob: its metadata plus a content stream. Always close it, ideally with
 * try-with-resources, to release the underlying connection.
 */
public final class Blob implements AutoCloseable {

    private final BlobMetadata metadata;
    private final InputStream content;

    public Blob(BlobMetadata metadata, InputStream content) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.content = Objects.requireNonNull(content, "content");
    }

    public BlobMetadata metadata() {
        return metadata;
    }

    public InputStream content() {
        return content;
    }

    public byte[] readAllBytes() {
        try {
            return content.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String readUtf8() {
        return new String(readAllBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        try {
            content.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
