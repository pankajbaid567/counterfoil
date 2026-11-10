import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Closed-loop throughput/latency benchmark over real HTTP against the real jar and a real PostgreSQL (fsync ON: every
 * commit waits for the WAL flush). Single file:  java tools/bench/Bench.java [--jar J] [--pg-bin B] [--seconds 20] [--clients 16]
 * Closed loop means each client sends its next request after the previous answer, so latencies here are service times under
 * that concurrency, not open-loop arrival-rate latencies. Everything runs on one machine: client, app and database compete for CPU.
 */
public class Bench {
    static final String KEY = "bench-key-0123456789abcdef";
    public static void main(String[] a) throws Exception {
        String jar = "target/counterfoil.jar", pgBin = "/opt/homebrew/opt/postgresql@16/bin", out = "tools/bench/results.csv";
        int seconds = 20, clients = 16;
        for (int i = 0; i < a.length; i++) switch (a[i]) {
            case "--jar" -> jar = a[++i]; case "--pg-bin" -> pgBin = a[++i];
            case "--seconds" -> seconds = Integer.parseInt(a[++i]); case "--clients" -> clients = Integer.parseInt(a[++i]);
            case "--out" -> out = a[++i];
            default -> throw new IllegalArgumentException(a[i]);
        }
        Path pg = Files.createTempDirectory("bench-pg");
        sh(pgBin + "/initdb", "-D", pg.toString(), "-U", "postgres", "-A", "trust", "-E", "UTF8");
        sh(pgBin + "/pg_ctl", "-D", pg.toString(), "-w", "-l", pg + "/pg.log", "-o", "-p 55433 -c unix_socket_directories=" + pg + " -c max_connections=100", "start");
        final String pgb = pgBin;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { sh(pgb + "/pg_ctl", "-D", pg.toString(), "-m", "immediate", "stop"); } catch (Exception ignored) { } }));
        sh(pgBin + "/createdb", "-h", "localhost", "-p", "55433", "-U", "postgres", "bench");
        ProcessBuilder pb = new ProcessBuilder("java", "-Xmx512m", "-XX:+UseSerialGC", "-jar", jar);
        pb.environment().putAll(Map.of("PORT", "18200", "DATABASE_URL", "jdbc:postgresql://localhost:55433/bench", "DATABASE_USER", "postgres",
                "DB_POOL_SIZE", "20", "COUNTERFOIL_API_KEY", KEY, "COUNTERFOIL_WEBHOOK_URL", "", "COUNTERFOIL_RECOVERY_INTERVAL_MS", "500"));
        pb.redirectErrorStream(true).redirectOutput(Path.of("tools/bench/app.log").toFile());
        Process app = pb.start();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long dl = System.currentTimeMillis() + 90_000;
        while (true) {
            try { if (call(http, "GET", "/v1/accounts/psp_clearing/balance", null, null) == 200) break; } catch (IOException ignored) { }
            if (System.currentTimeMillis() > dl) throw new IllegalStateException("app did not start");
            Thread.sleep(200);
        }
        int accounts = 200;
        for (int i = 0; i < accounts; i++) call(http, "POST", "/v1/accounts", null, "{\"code\":\"b-" + i + "\",\"name\":\"b\",\"type\":\"ASSET\",\"currency\":\"INR\",\"allow_negative\":true}");
        call(http, "POST", "/v1/accounts", null, "{\"code\":\"b-eq\",\"name\":\"eq\",\"type\":\"EQUITY\",\"currency\":\"INR\",\"allow_negative\":true}");

        PrintWriter csv = new PrintWriter(new FileWriter(out));
        csv.println("workload,clients,seconds,requests,errors,req_per_s,p50_ms,p95_ms,p99_ms,max_ms");
        // warm-up so the JIT and connection pool are not part of the numbers
        run(http, "journal", 8, 5, accounts, null);
        for (String w : new String[]{"journal", "payment-flow"}) {
            String line = run(http, w, clients, seconds, accounts, csv);
            System.out.println(line);
        }
        csv.close();
        app.destroyForcibly();
        System.exit(0);
    }

    static String run(HttpClient http, String workload, int clients, int seconds, int accounts, PrintWriter csv) throws Exception {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        List<long[]> lat = Collections.synchronizedList(new ArrayList<>());
        AtomicLong errors = new AtomicLong(), n = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<Future<?>> fs = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            final int id = c;
            fs.add(pool.submit(() -> {
                Random r = new Random(id * 31L + 7);
                long[] mine = new long[2_000_000]; int m = 0;
                while (System.nanoTime() < end) {
                    String k = UUID.randomUUID().toString();
                    try {
                        if (workload.equals("journal")) {
                            int x = r.nextInt(accounts), y = r.nextInt(accounts);
                            if (x == y) continue;
                            long amt = 1 + r.nextInt(1000);
                            String body = "{\"lines\":[{\"account\":\"b-" + x + "\",\"side\":\"D\",\"amount\":" + amt + "},{\"account\":\"b-" + y + "\",\"side\":\"C\",\"amount\":" + amt + "}]}";
                            long t = System.nanoTime();
                            int s = call(http, "POST", "/v1/journal-entries", k, body);
                            mine[m++] = System.nanoTime() - t;
                            if (s != 201) errors.incrementAndGet();
                            n.incrementAndGet();
                        } else {
                            // one payment through create -> authorize -> capture; each request is timed and counted on its own
                            String id1 = null;
                            long t = System.nanoTime();
                            String[] res = callBody(http, "POST", "/v1/payments", k, "{\"merchant_id\":\"bm" + (id % 20) + "\",\"amount\":" + (1000 + r.nextInt(50000)) + ",\"currency\":\"INR\"}");
                            mine[m++] = System.nanoTime() - t; n.incrementAndGet();
                            if (!res[0].equals("201")) { errors.incrementAndGet(); continue; }
                            id1 = res[1].replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
                            for (String step : new String[]{"authorize", "capture"}) {
                                t = System.nanoTime();
                                int s = call(http, "POST", "/v1/payments/" + id1 + "/" + step, UUID.randomUUID().toString(), null);
                                mine[m++] = System.nanoTime() - t; n.incrementAndGet();
                                if (s != 200) { errors.incrementAndGet(); break; }
                            }
                        }
                    } catch (IOException e) { errors.incrementAndGet(); }
                    if (m >= mine.length - 4) break;
                }
                lat.add(Arrays.copyOf(mine, m));
            }));
        }
        for (var f : fs) f.get();
        pool.shutdown();
        long total = lat.stream().mapToLong(x -> x.length).sum();
        long[] all = new long[(int) total]; int p = 0;
        for (long[] x : lat) { System.arraycopy(x, 0, all, p, x.length); p += x.length; }
        Arrays.sort(all);
        double ms = 1e6;
        String line = String.format("%s,%d,%d,%d,%d,%.0f,%.2f,%.2f,%.2f,%.2f", workload, clients, seconds, total, errors.get(), total / (double) seconds,
                all[(int) (total * 0.50)] / ms, all[(int) (total * 0.95)] / ms, all[(int) Math.min(total - 1, (long) (total * 0.99))] / ms, all[(int) total - 1] / ms);
        if (csv != null) csv.println(line);
        return line;
    }

    static int call(HttpClient h, String m, String path, String idem, String body) throws IOException { return Integer.parseInt(callBody(h, m, path, idem, body)[0]); }

    static String[] callBody(HttpClient h, String m, String path, String idem, String body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:18200" + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + KEY).header("Content-Type", "application/json");
        if (idem != null) b.header("Idempotency-Key", idem);
        b.method(m, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try { var r = h.send(b.build(), HttpResponse.BodyHandlers.ofString()); return new String[]{"" + r.statusCode(), r.body()}; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }

    static String sh(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IOException(String.join(" ", cmd) + "\n" + o);
        return o;
    }
}
