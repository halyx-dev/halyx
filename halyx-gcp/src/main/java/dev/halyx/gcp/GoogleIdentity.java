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

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import dev.halyx.config.Credentials;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;

/** The Google credentials a provider acts with: Application Default Credentials, or a service account they impersonate. */
final class GoogleIdentity {

    static final String CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform";

    /** Google's default for impersonated tokens; longer needs an organization policy. */
    static final Duration DEFAULT_LIFETIME = Duration.ofHours(1);

    private GoogleIdentity() {
    }

    /** Whether these credentials give a Google identity: the default chain, or impersonation from it. */
    static boolean isIdentity(Credentials credentials) {
        return credentials instanceof Credentials.DefaultChain || credentials instanceof Credentials.Impersonation;
    }

    static GoogleCredentials credentials(Credentials credentials) {
        return switch (credentials) {
            case Credentials.DefaultChain ignored -> applicationDefault();
            case Credentials.Impersonation impersonation -> impersonate(impersonation, applicationDefault());
            case Credentials.Static ignored -> throw new IllegalArgumentException(
                    "GCP does not use static key pairs; use Credentials.defaultChain() with "
                            + "GOOGLE_APPLICATION_CREDENTIALS or workload identity");
            case Credentials.ServicePrincipal ignored -> throw new IllegalArgumentException("A Microsoft Entra ID "
                    + "service principal logs in to Azure, not Google Cloud: use the default chain or impersonation");
            case Credentials.Anonymous ignored -> throw new IllegalArgumentException("This needs a Google identity, "
                    + "not anonymous access: use the default chain or impersonation");
        };
    }

    /** Short-lived tokens for the target service account, refreshed by the source credentials. */
    static ImpersonatedCredentials impersonate(Credentials.Impersonation impersonation, GoogleCredentials source) {
        if (!(impersonation.source() instanceof Credentials.DefaultChain)) {
            throw new IllegalArgumentException("On Google Cloud, impersonation starts from Application Default "
                    + "Credentials: AWS access keys can't be used");
        }
        if (impersonation.sessionName() != null || impersonation.externalId() != null) {
            throw new IllegalArgumentException("A session name and an external ID are AWS settings: Google Cloud "
                    + "impersonation takes only the service account and a lifetime");
        }
        if (!impersonation.target().contains("@")) {
            throw new IllegalArgumentException("On Google Cloud, impersonation acts as a service account: pass its "
                    + "email, such as orders@my-project.iam.gserviceaccount.com, not '" + impersonation.target() + "'");
        }
        Duration lifetime = impersonation.lifetime() != null ? impersonation.lifetime() : DEFAULT_LIFETIME;
        return ImpersonatedCredentials.create(source, impersonation.target(), null, List.of(CLOUD_PLATFORM),
                Math.toIntExact(lifetime.toSeconds()));
    }

    private static GoogleCredentials applicationDefault() {
        try {
            return GoogleCredentials.getApplicationDefault();
        } catch (IOException e) {
            throw new UncheckedIOException("No Google Application Default Credentials", e);
        }
    }
}
