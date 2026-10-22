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
package dev.halyx.messaging;

/** A running listener on a subscription; {@link #close()} stops it. */
public interface MessageConsumer extends AutoCloseable {

    boolean isRunning();

    ConsumerStatistics statistics();

    /**
     * Stops pulling, lets running handlers finish (up to the shutdown timeout) and nacks any message received
     * but not yet started, so it is redelivered promptly. Called from one of this consumer's handlers, it returns
     * at once, and the consumer stops once that handler returns.
     */
    @Override
    void close();
}
