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
package org.kamranzafar.halyx.redis;

import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Adapts a {@link RedisCredentialsSource} to Lettuce. Logins are reused until shortly before they
 * expire. For expiring tokens, a fresh token is minted ahead of expiry and published on
 * {@link #credentials()}, so Lettuce re-authenticates open connections (with
 * {@code ReauthenticateBehavior.ON_NEW_CREDENTIALS}) instead of letting them be dropped.
 */
final class RefreshingCredentialsProvider implements RedisCredentialsProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshingCredentialsProvider.class);

    /** How long before expiry a token is replaced. */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(2);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(10);

    private final RedisCredentialsSource source;
    private final Sinks.Many<RedisCredentials> updates = Sinks.many().replay().latest();
    private final ScheduledExecutorService scheduler;
    private RedisLogin current;

    RefreshingCredentialsProvider(RedisCredentialsSource source) {
        this.source = source;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "halyx-redis-credentials");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public Mono<RedisCredentials> resolveCredentials() {
        return Mono.fromCallable(() -> toLettuce(currentLogin()));
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public Flux<RedisCredentials> credentials() {
        return updates.asFlux();
    }

    synchronized RedisLogin currentLogin() {
        if (current == null || expiringSoon(current)) {
            current = source.login();
            schedule(current);
        }
        return current;
    }

    private void schedule(RedisLogin login) {
        login.expiresAt().ifPresent(expiry -> {
            long delay = Math.max(0, Duration.between(Instant.now(), expiry.minus(REFRESH_MARGIN)).toMillis());
            scheduler.schedule(this::refresh, delay, TimeUnit.MILLISECONDS);
        });
    }

    private void refresh() {
        try {
            RedisLogin fresh;
            synchronized (this) {
                fresh = source.login();
                current = fresh;
            }
            updates.tryEmitNext(toLettuce(fresh));
            schedule(fresh);
        } catch (RuntimeException e) {
            LOG.warn("Could not refresh cache credentials; retrying in {}", RETRY_DELAY, e);
            scheduler.schedule(this::refresh, RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static boolean expiringSoon(RedisLogin login) {
        return login.expiresAt().map(expiry -> Instant.now().isAfter(expiry.minus(REFRESH_MARGIN))).orElse(false);
    }

    private static RedisCredentials toLettuce(RedisLogin login) {
        return RedisCredentials.just(login.username().orElse(null), login.password());
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        updates.tryEmitComplete();
    }
}
