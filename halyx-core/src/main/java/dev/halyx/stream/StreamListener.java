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
package dev.halyx.stream;

/** A running listener on a consumer; {@link #close()} stops it. */
public interface StreamListener extends AutoCloseable {

    boolean isRunning();

    ListenerStatistics statistics();

    /**
     * Stops reading, lets records being handled finish (up to the shutdown timeout) and commits what was
     * handled. Records read but not handled are read again by whichever member owns the partition next.
     */
    @Override
    void close();
}
