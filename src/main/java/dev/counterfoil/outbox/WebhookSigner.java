package dev.counterfoil.outbox;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signature header format: {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">}. */
public final class WebhookSigner {
    private WebhookSigner() {}

    public static String sign(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + hmac(secret, timestamp + "." + body);
    }

    /** True only for a well-formed header, a matching signature (constant-time compare) and a timestamp within tolerance. */
    public static boolean verify(String secret, String header, String body, long nowSeconds, long toleranceSeconds) {
        if (header == null || body == null) {
            return false;
        }
        long t = -1;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                return false;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t < 0 || v1 == null || Math.abs(nowSeconds - t) > toleranceSeconds) {
            return false;
        }
        byte[] expected = hmac(secret, t + "." + body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, v1.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
