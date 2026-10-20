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
package dev.halyx.samples.orders;

import dev.halyx.error.NotFoundException;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import dev.halyx.storage.PutOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Receipts in the {@code receipts} blob store, signed with HMAC-SHA256 under the {@code receipt-signing-key} secret
 * from the {@code secrets} parameter store. The store caches the secret, and a rotated key is picked up when its cache
 * entry expires.
 */
@Component
public class ReceiptService {

    static final String SIGNING_KEY = "receipt-signing-key";

    private final BlobStore receipts;
    private final ParameterStore secrets;

    ReceiptService(@Qualifier("receipts") BlobStore receipts, @Qualifier("secrets") ParameterStore secrets) {
        this.receipts = receipts;
        this.secrets = secrets;
    }

    /** Writes the order's receipt and returns its blob key. Writing it again replaces it with the same content. */
    String write(Order order) {
        StringBuilder text = new StringBuilder()
                .append("Receipt for order ").append(order.orderId()).append('\n')
                .append("Customer: ").append(order.customerId()).append('\n')
                .append("Placed: ").append(order.placedAt()).append('\n');
        for (OrderLine line : order.lines()) {
            text.append(line.quantity()).append(" x ").append(line.sku()).append(" @ ")
                    .append(money(line.unitPrice())).append('\n');
        }
        text.append("Total: ").append(money(order.total())).append(' ').append(order.currency()).append('\n');
        String body = text.toString();
        String signed = body + "Signature: " + sign(body, secrets.require(SIGNING_KEY)) + '\n';
        BlobKey key = key(order.customerId(), order.orderId());
        receipts.put(key, BlobContent.ofString(signed), PutOptions.builder().contentType("text/plain").build());
        return key.value();
    }

    Optional<String> read(String customerId, String orderId) {
        try {
            return Optional.of(new String(receipts.getBytes(key(customerId, orderId)), StandardCharsets.UTF_8));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    /** HMAC-SHA256 of the receipt's text, in hex. */
    public static String sign(String body, String signingKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Amounts with two decimals: document stores keep numbers, not their scale, so 3.50 can read back as 3.5. */
    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BlobKey key(String customerId, String orderId) {
        return BlobKey.of("receipts/" + customerId + "/" + orderId + ".txt");
    }
}
