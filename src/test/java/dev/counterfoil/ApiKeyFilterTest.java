package dev.counterfoil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.counterfoil.api.ApiKeyFilter;
import dev.counterfoil.config.CounterfoilProperties;
import org.junit.jupiter.api.Test;

class ApiKeyFilterTest {
    static CounterfoilProperties props(String apiKey, String webhookSecret) {
        var d = CounterfoilProperties.defaults();
        return new CounterfoilProperties(apiKey, new CounterfoilProperties.Webhook(webhookSecret, "", true, 8, 300, 500, 30), d.psp(), d.recovery(), d.fees());
    }

    @Test
    void refusesToStartInProductionModeWithAPublicWebhookSecret() {
        var m = new ObjectMapper();
        assertThatThrownBy(() -> new ApiKeyFilter(props("a-long-enough-api-key-123", "dev-only-webhook-secret"), m, false)).hasMessageContaining("WEBHOOK_SECRET");
        assertThatThrownBy(() -> new ApiKeyFilter(props("a-long-enough-api-key-123", "short"), m, false)).hasMessageContaining("WEBHOOK_SECRET");
        assertThatThrownBy(() -> new ApiKeyFilter(props("", "x"), m, false)).hasMessageContaining("API_KEY");
        assertThatThrownBy(() -> new ApiKeyFilter(props("short", "a-random-webhook-secret-0123"), m, false)).hasMessageContaining("16 characters");
        assertThat(new ApiKeyFilter(props("a-long-enough-api-key-123", "a-random-webhook-secret-0123"), m, false)).isNotNull();
        assertThat(new ApiKeyFilter(props("", "dev-only-webhook-secret"), m, true)).as("explicit local-development mode").isNotNull();
    }
}
