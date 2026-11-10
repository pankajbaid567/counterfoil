package dev.counterfoil.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.TreeMap;

/** Hashes a request so that the same logical request always hashes the same, whatever its field order. */
public final class RequestHasher {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestHasher() {}

    public static String hash(String method, String path, String jsonBody) {
        try {
            String canonical = jsonBody == null || jsonBody.isBlank() ? "" : MAPPER.writeValueAsString(sort(MAPPER.readTree(jsonBody)));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((method + "\n" + path + "\n" + canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // an unparseable body hashes as its raw text, so retries of the same garbage still match
            return sha(method + "\n" + path + "\n" + jsonBody);
        }
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode sort(JsonNode n) {
        if (n.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
                String f = it.next();
                sorted.put(f, sort(n.get(f)));
            }
            ObjectNode out = MAPPER.createObjectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (n.isArray()) {
            var out = MAPPER.createArrayNode();
            n.forEach(x -> out.add(sort(x)));
            return out;
        }
        return n;
    }
}
