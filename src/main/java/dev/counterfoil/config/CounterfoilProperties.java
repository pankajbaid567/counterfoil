package dev.counterfoil.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("counterfoil")
public record CounterfoilProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue Webhook webhook,
        @DefaultValue Psp psp,
        @DefaultValue Recovery recovery,
        @DefaultValue Fees fees) {

    public record Webhook(@DefaultValue("dev-only-webhook-secret") String secret, @DefaultValue("") String targetUrl,
                          @DefaultValue("true") boolean dispatcherEnabled, @DefaultValue("8") int maxAttempts,
                          @DefaultValue("300") long toleranceSeconds, @DefaultValue("500") long backoffBaseMs,
                          @DefaultValue("30") long leaseSeconds) {}

    public record Psp(@DefaultValue("1") long seed, @DefaultValue("0") double pDecline, @DefaultValue("0") double pTimeoutBefore,
                      @DefaultValue("0") double pTimeoutAfter) {}

    public record Recovery(@DefaultValue("20") long staleAfterSeconds, @DefaultValue("2000") long intervalMs) {}

    public record Fees(@DefaultValue("200") int bps) {}

    public static CounterfoilProperties defaults() {
        return new CounterfoilProperties("", new Webhook("dev-only-webhook-secret", "", true, 8, 300, 500, 30), new Psp(1, 0, 0, 0),
                new Recovery(20, 2000), new Fees(200));
    }
}
