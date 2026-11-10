package dev.counterfoil.api;

import dev.counterfoil.outbox.WebhookSender;
import dev.counterfoil.payments.PaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
public class Wiring {
    @Bean
    WebhookSender webhookSender() {
        return WebhookSender.http();
    }

    /**
     * Creates the fixed chart of accounts on boot (idempotent). Runs after all singletons exist but before the web
     * server starts accepting requests, so no request can arrive while the system accounts are missing.
     */
    @Bean
    SmartInitializingSingleton systemAccounts(PaymentService payments) {
        return payments::ensureSystemAccounts;
    }

    /** Finishes payment operations whose PSP answer never arrived or whose process died mid-way. */
    @Component
    public static class RecoverySweeper {
        private final PaymentService payments;
        private final long staleSeconds;

        public RecoverySweeper(PaymentService payments, dev.counterfoil.config.CounterfoilProperties props) {
            this.payments = payments;
            this.staleSeconds = props.recovery().staleAfterSeconds();
        }

        @Scheduled(fixedDelayString = "${counterfoil.recovery.interval-ms:2000}")
        public void sweep() {
            payments.recoverStale(staleSeconds, 50);
        }
    }
}
