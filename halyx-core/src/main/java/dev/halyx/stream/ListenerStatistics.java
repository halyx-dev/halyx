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

/**
 * Counters of a running listener.
 *
 * @param received           records read from the cluster
 * @param handled            records handled successfully
 * @param failed             listener invocations that threw
 * @param deadLettered       records sent to the dead-letter producer after the last attempt
 * @param inFlight           records being handled now
 * @param pollErrors         failed reads (the listener backs off and keeps reading)
 * @param assignedPartitions partitions this member owns now
 */
public record ListenerStatistics(long received, long handled, long failed, long deadLettered, int inFlight,
                                 long pollErrors, int assignedPartitions) {
}
