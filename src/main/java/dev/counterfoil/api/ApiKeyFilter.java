package dev.counterfoil.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.counterfoil.config.CounterfoilProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer-token auth for /v1/**. The PSP webhook is exempt because it authenticates by HMAC signature instead.
 * With no key configured the app refuses to start, unless counterfoil.allow-no-auth=true (local development only).
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    public static final String PSP_WEBHOOK_PATH = "/v1/webhooks/psp";

    private final byte[] key;
    private final boolean open;
    private final ObjectMapper mapper;

    public ApiKeyFilter(CounterfoilProperties props, ObjectMapper mapper, @Value("${counterfoil.allow-no-auth:false}") boolean allowNoAuth) {
        this.mapper = mapper;
        String k = props.apiKey();
        if (k == null || k.isBlank()) {
            if (!allowNoAuth) {
                throw new IllegalStateException("COUNTERFOIL_API_KEY is not set. Set it, or set COUNTERFOIL_ALLOW_NO_AUTH=true for local development only.");
            }
            this.open = true;
            this.key = new byte[0];
        } else {
            if (k.length() < 16) {
                throw new IllegalStateException("COUNTERFOIL_API_KEY must be at least 16 characters");
            }
            // With authentication on, the inbound PSP webhook is authenticated only by this secret. The built-in default is
            // public (it is in the source), so running with it would let anyone forge settlement events.
            String ws = props.webhook().secret();
            if (ws == null || ws.length() < 16 || ws.equals("dev-only-webhook-secret")) {
                throw new IllegalStateException("COUNTERFOIL_WEBHOOK_SECRET must be set to a random value of at least 16 characters");
            }
            this.open = false;
            this.key = sha(k);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !p.startsWith("/v1/") || p.equals(PSP_WEBHOOK_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (!open) {
            String h = req.getHeader("Authorization");
            boolean ok = h != null && h.startsWith("Bearer ") && MessageDigest.isEqual(key, sha(h.substring(7).trim()));
            if (!ok) {
                res.setStatus(401);
                res.setContentType("application/json");
                res.setHeader("WWW-Authenticate", "Bearer");
                mapper.writeValue(res.getOutputStream(), Map.of("error", Map.of("code", "UNAUTHORIZED", "message", "missing or invalid API key")));
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private static byte[] sha(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
