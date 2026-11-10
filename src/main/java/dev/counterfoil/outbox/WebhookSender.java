package dev.counterfoil.outbox;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** How a webhook leaves the process. An interface so tests can inject faults. */
public interface WebhookSender {
    /** Returns the HTTP status of the receiver; throws on network failure or timeout. */
    int post(String url, Map<String, String> headers, String body) throws IOException;

    /** The real HTTP implementation: short timeouts, no redirects followed. */
    static WebhookSender http() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        return (url, headers, body) -> {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(rb::header);
            try {
                return client.send(rb.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }
}
