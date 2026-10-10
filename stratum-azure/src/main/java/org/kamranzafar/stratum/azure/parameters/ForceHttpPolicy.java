package org.kamranzafar.stratum.azure.parameters;

import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import reactor.core.publisher.Mono;

/**
 * Rewrites {@code https} to {@code http}, for local emulators only: the Key Vault and App
 * Configuration SDKs refuse plain-HTTP endpoints. Enabled with the {@code azure.emulator.insecure-http}
 * store property.
 */
public final class ForceHttpPolicy implements HttpPipelinePolicy {

    @Override
    public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
        String url = context.getHttpRequest().getUrl().toString();
        if (url.startsWith("https://")) {
            context.getHttpRequest().setUrl("http://" + url.substring("https://".length()));
        }
        return next.process();
    }
}
