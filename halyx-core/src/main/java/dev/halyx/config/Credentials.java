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
package dev.halyx.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * How a provider authenticates. Each provider maps these onto its native credential chain.
 *
 * <ul>
 *   <li>{@link DefaultChain}: the platform default (AWS default chain, {@code DefaultAzureCredential},
 *       Google Application Default Credentials). This is the right choice when running on the cloud.</li>
 *   <li>{@link Static}: an explicit id/secret pair (AWS access key, Azure storage account name and key).</li>
 *   <li>{@link Impersonation}: act as another identity, starting from the default chain or an access key: an AWS
 *       IAM role through STS, or a Google service account.</li>
 *   <li>{@link ServicePrincipal}: a Microsoft Entra ID application, with a client secret, a certificate or a
 *       federated token file, for code running outside Azure.</li>
 *   <li>{@link Anonymous}: no credentials, for public resources and local emulators that do not check auth.</li>
 * </ul>
 */
public sealed interface Credentials {

    static Credentials defaultChain() {
        return DefaultChain.INSTANCE;
    }

    static Credentials of(String id, String secret) {
        return new Static(id, secret);
    }

    static Credentials anonymous() {
        return Anonymous.INSTANCE;
    }

    /**
     * Act as {@code target}, starting from the default chain: an IAM role ARN on AWS, or a service account email
     * on Google Cloud.
     */
    static Impersonation impersonate(String target) {
        return new Impersonation(target, defaultChain(), null, null, null);
    }

    /** A Microsoft Entra ID application that logs in with a client secret. */
    static ServicePrincipal servicePrincipal(String tenantId, String clientId, String clientSecret) {
        return new ServicePrincipal(tenantId, clientId, Objects.requireNonNull(clientSecret, "clientSecret"), null, null);
    }

    /** A Microsoft Entra ID application that logs in with a PEM certificate holding its private key. */
    static ServicePrincipal servicePrincipalWithCertificate(String tenantId, String clientId, Path certificate) {
        return new ServicePrincipal(tenantId, clientId, null, Objects.requireNonNull(certificate, "certificate"), null);
    }

    /**
     * A Microsoft Entra ID application that logs in with a federated token read from a file, such as the
     * service account token Kubernetes projects into a pod on EKS or GKE. Entra ID trusts the token's issuer
     * through a federated identity credential on the application, so no secret is stored.
     */
    static ServicePrincipal servicePrincipalWithFederatedToken(String tenantId, String clientId, Path tokenFile) {
        return new ServicePrincipal(tenantId, clientId, null, null, Objects.requireNonNull(tokenFile, "tokenFile"));
    }

    record DefaultChain() implements Credentials {
        static final DefaultChain INSTANCE = new DefaultChain();
    }

    record Static(String id, String secret) implements Credentials {
        public Static {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(secret, "secret");
        }

        @Override
        public String toString() {
            return "Static[id=" + id + ", secret=****]";
        }
    }

    /**
     * Act as another identity, with temporary credentials that are refreshed before they expire.
     *
     * @param target      an IAM role ARN (AWS) or a service account email (Google Cloud)
     * @param source      the credentials that are allowed to act as the target: the default chain, or an AWS
     *                    access key
     * @param sessionName the role session name, shown in CloudTrail (AWS only); {@code halyx} when null
     * @param externalId  the external ID the role's trust policy requires (AWS only); none when null
     * @param lifetime    how long each set of temporary credentials lasts; the cloud's default (an hour) when null
     */
    record Impersonation(String target, Credentials source, String sessionName, String externalId, Duration lifetime)
            implements Credentials {

        public Impersonation {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(source, "source");
            if (target.isBlank()) {
                throw new IllegalArgumentException("The identity to impersonate is blank");
            }
            if (!(source instanceof DefaultChain || source instanceof Static)) {
                throw new IllegalArgumentException("Impersonation starts from the default chain or an access key, not "
                        + source.getClass().getSimpleName());
            }
            if (lifetime != null && (lifetime.isNegative() || lifetime.isZero())) {
                throw new IllegalArgumentException("The impersonation lifetime must be positive: " + lifetime);
            }
        }

        /** Start from these credentials instead of the default chain. */
        public Impersonation from(Credentials source) {
            return new Impersonation(target, source, sessionName, externalId, lifetime);
        }

        public Impersonation sessionName(String sessionName) {
            return new Impersonation(target, source, Objects.requireNonNull(sessionName, "sessionName"), externalId, lifetime);
        }

        public Impersonation externalId(String externalId) {
            return new Impersonation(target, source, sessionName, Objects.requireNonNull(externalId, "externalId"), lifetime);
        }

        public Impersonation lifetime(Duration lifetime) {
            return new Impersonation(target, source, sessionName, externalId, Objects.requireNonNull(lifetime, "lifetime"));
        }

        @Override
        public String toString() {
            return "Impersonation[target=" + target + ", source=" + source + ", sessionName=" + sessionName
                    + ", externalId=" + (externalId == null ? null : "****") + ", lifetime=" + lifetime + "]";
        }
    }

    /**
     * A Microsoft Entra ID application (service principal). Exactly one of {@code clientSecret},
     * {@code certificate} and {@code federatedTokenFile} is set.
     */
    record ServicePrincipal(String tenantId, String clientId, String clientSecret, Path certificate, Path federatedTokenFile)
            implements Credentials {

        public ServicePrincipal {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(clientId, "clientId");
            int set = (clientSecret != null ? 1 : 0) + (certificate != null ? 1 : 0) + (federatedTokenFile != null ? 1 : 0);
            if (set != 1) {
                throw new IllegalArgumentException("A service principal needs exactly one of a client secret, "
                        + "a certificate and a federated token file");
            }
        }

        @Override
        public String toString() {
            return "ServicePrincipal[tenantId=" + tenantId + ", clientId=" + clientId
                    + (clientSecret != null ? ", clientSecret=****" : "")
                    + (certificate != null ? ", certificate=" + certificate : "")
                    + (federatedTokenFile != null ? ", federatedTokenFile=" + federatedTokenFile : "") + "]";
        }
    }

    record Anonymous() implements Credentials {
        static final Anonymous INSTANCE = new Anonymous();
    }
}
