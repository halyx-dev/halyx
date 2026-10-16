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
package dev.halyx.spring.autoconfigure.reactor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code halyx.reactor.*}: the {@code ReactiveHalyx} bean and the reactive views of the service beans.
 *
 * @param enabled        register the reactive beans when {@code halyx-reactor} is on the classpath (default true)
 * @param scheduler      where calls run: {@code virtual-threads} (default, one virtual thread per call),
 *                       {@code bounded-elastic} (Reactor's shared scheduler), or the name of a {@code Scheduler}
 *                       bean of the application's
 * @param maxConcurrency most calls running at once across the reactive beans; 0, the default, means no limit
 */
@ConfigurationProperties("halyx.reactor")
public record HalyxReactorProperties(@DefaultValue("true") boolean enabled,
                                     @DefaultValue("virtual-threads") String scheduler,
                                     @DefaultValue("0") int maxConcurrency) {
}
