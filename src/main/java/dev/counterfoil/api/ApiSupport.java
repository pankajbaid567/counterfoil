package dev.counterfoil.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.counterfoil.idempotency.IdempotencyService.Outcome;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Shared request plumbing: strict JSON parsing, Idempotency-Key validation, rendering stored responses. */
@Component
public class ApiSupport {
    public static final String SCOPE = "api";
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:\\-]{1,255}$");

    private final ObjectMapper strict;

    public ApiSupport(ObjectMapper mapper) {
        this.strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public <T> T parse(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            throw ApiException.bad("request body is required");
        }
        try {
            return strict.readValue(body, type);
        } catch (Exception e) {
            throw ApiException.bad("invalid JSON body: " + firstLine(e.getMessage()));
        }
    }

    public String key(String header) {
        if (header == null || !KEY.matcher(header).matches()) {
            throw ApiException.bad("Idempotency-Key must be 1-255 characters of A-Z a-z 0-9 . _ : -");
        }
        return header;
    }

    public static <T> T required(T v, String name) {
        if (v == null) {
            throw ApiException.bad("'" + name + "' is required");
        }
        return v;
    }

    public ResponseEntity<String> render(Outcome o) {
        var b = ResponseEntity.status(o.response().status()).contentType(MediaType.APPLICATION_JSON);
        if (o.replayed()) {
            b.header("Idempotent-Replayed", "true");
        }
        return b.body(o.response().body());
    }

    public ResponseEntity<String> json(int status, Object value) {
        try {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(strict.writeValueAsString(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        String line = i < 0 ? s : s.substring(0, i);
        return line.length() > 200 ? line.substring(0, 200) : line;
    }
}
