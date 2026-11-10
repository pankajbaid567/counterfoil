package dev.counterfoil.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A landing page for people who open the service URL in a browser. No data, no authentication needed. */
@RestController
public class RootController {
    private static final String PAGE = """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Counterfoil</title>
            <style>:root{color-scheme:light dark}body{font:16px/1.55 system-ui,sans-serif;max-width:42rem;margin:3rem auto;padding:0 1rem}
            code{background:rgba(127,127,127,.18);padding:.1rem .35rem;border-radius:4px}a{color:inherit}</style></head><body>
            <h1>Counterfoil</h1>
            <p>A double-entry ledger and payment back office (a simulator: no real money, no real payment provider) that stays correct
            when requests are retried, webhooks arrive twice and the process is killed mid-payment.</p>
            <p>This URL is the <strong>API</strong>, so there is no web UI. Every <code>/v1/*</code> call needs a Bearer API key.</p>
            <ul>
            <li><a href="/actuator/health">/actuator/health</a>: liveness and database check</li>
            <li><a href="https://github.com/pankajbaid567/counterfoil">Source, API reference and results on GitHub</a>:
            1,000 SIGKILL runs with 0 violations, 7 of 7 injected bugs caught</li>
            </ul>
            <p>On a free tier the first request after a quiet spell can take up to a minute while the service wakes up.</p>
            </body></html>
            """;

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        return PAGE;
    }
}
