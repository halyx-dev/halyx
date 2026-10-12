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
package org.kamranzafar.halyx.storage;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes blob content to a stream, for {@link BlobStore#write}. The stream is managed by Halyx:
 * the blob is committed only after this method returns normally, and discarded if it throws.
 * Closing the stream (for example by a wrapping {@code GZIPOutputStream}) does not commit it.
 */
@FunctionalInterface
public interface BlobSink {

    void writeTo(OutputStream out) throws IOException;
}
