import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Crash-consistency harness for Counterfoil. Single file, no dependencies:  java tools/crashtest/CrashTest.java [options]
 *
 * It starts a throwaway PostgreSQL cluster, then per worker (own database, own app port) repeats:
 *   start the real app JVM -> verify (nothing acknowledged was lost, invariants hold) -> let client threads drive a
 *   mixed workload for a random time -> SIGKILL the JVM at a random moment -> restart.
 * Client threads keep their in-flight request and retry it with the SAME Idempotency-Key after every restart, exactly as a
 * real client would. The harness talks to the app over HTTP and checks the database directly with psql.
 * Only the application process is killed. PostgreSQL's own crash safety is PostgreSQL's, and is not tested here.
 */
public class CrashTest {
    static String jar = "target/counterfoil.jar", pgBin = "/opt/homebrew/opt/postgresql@16/bin", outDir = "tools/crashtest/results";
    static int workers = 4, cyclesPerWorker = 25, clientsPerWorker = 6, killMinMs = 300, killMaxMs = 2500, pgPort = 55432, basePort = 18100;
    static long seed = 42;
    static final String API_KEY = "crashtest-key-0123456789", WH_SECRET = "crashtest-webhook-secret";
    static final Duration PSQL_TIMEOUT = Duration.ofSeconds(30);

    static final ConcurrentHashMap<String, AtomicInteger> received = new ConcurrentHashMap<>();   // webhook event id -> deliveries seen
    static final AtomicInteger badSignatures = new AtomicInteger();
    static final List<String> violations = Collections.synchronizedList(new ArrayList<>());
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static PrintWriter csv;
    static volatile boolean abort = false;

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--jar" -> jar = args[++i];
                case "--pg-bin" -> pgBin = args[++i];
                case "--workers" -> workers = Integer.parseInt(args[++i]);
                case "--cycles" -> cyclesPerWorker = Integer.parseInt(args[++i]);
                case "--clients" -> clientsPerWorker = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--kill-min-ms" -> killMinMs = Integer.parseInt(args[++i]);
                case "--kill-max-ms" -> killMaxMs = Integer.parseInt(args[++i]);
                case "--out" -> outDir = args[++i];
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (!Files.exists(Path.of(jar))) throw new FileNotFoundException(jar + " (run ./mvnw package -DskipTests first)");
        Files.createDirectories(Path.of(outDir));
        String stamp = Instant.now().toString().replace(":", "").replace("-", "").substring(0, 15);
        csv = new PrintWriter(new FileWriter(outDir + "/crash-" + stamp + ".csv"), true);
        csv.println("worker,cycle,kind,kill_after_ms,tasks_done,tasks_in_flight,acked_checked,result");

        Path pgData = Files.createTempDirectory("crashtest-pg");
        sh(pgBin + "/initdb", "-D", pgData.toString(), "-U", "postgres", "-A", "trust", "--no-sync", "-E", "UTF8");
        sh(pgBin + "/pg_ctl", "-D", pgData.toString(), "-w", "-l", pgData + "/pg.log", "-o",
                "-p " + pgPort + " -c fsync=off -c max_connections=300 -c shared_buffers=128MB -c unix_socket_directories=" + pgData, "start");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { sh(pgBin + "/pg_ctl", "-D", pgData.toString(), "-m", "immediate", "stop"); } catch (Exception ignored) { }
        }));

        HttpServer receiver = startReceiver();
        int receiverPort = receiver.getAddress().getPort();

        long t0 = System.currentTimeMillis();
        List<Worker> ws = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 1; i <= workers; i++) {
            Worker w = new Worker(i, receiverPort);
            ws.add(w);
            fs.add(pool.submit(w));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        receiver.stop(0);

        int runs = ws.stream().mapToInt(w -> w.runs).sum();
        int tasks = ws.stream().mapToInt(w -> w.tasksDone.get()).sum();
        long dupDeliveries = received.values().stream().filter(c -> c.get() > 1).count();
        String summary = String.format("runs=%d (kill+restart+verify cycles) workers=%d seconds=%d tasks_completed=%d violations=%d bad_signatures=%d "
                        + "webhook_events_received=%d events_delivered_more_than_once=%d",
                runs, workers, (System.currentTimeMillis() - t0) / 1000, tasks, violations.size(), badSignatures.get(), received.size(), dupDeliveries);
        System.out.println(summary);
        csv.println("# " + summary);
        if (!violations.isEmpty()) {
            Files.write(Path.of(outDir + "/violations-" + stamp + ".txt"), violations);
            System.out.println("FIRST VIOLATIONS:");
            violations.stream().limit(10).forEach(v -> System.out.println("  " + v));
            System.exit(1);
        }
        System.out.println("PASS");
        System.exit(0);
    }

    // ------------------------------------------------------------------ webhook receiver (the "merchant")
    static HttpServer startReceiver() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setExecutor(Executors.newFixedThreadPool(8));
        s.createContext("/hook", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            String text = new String(body, StandardCharsets.UTF_8);
            String sig = ex.getRequestHeaders().getFirst("Counterfoil-Signature");
            int status = 200;
            Matcher id = Pattern.compile("\"id\":\"([0-9a-f\\-]{36})\"").matcher(text);
            if (!verify(sig, text) || !id.find()) {
                badSignatures.incrementAndGet();
                status = 400;
            } else {
                received.computeIfAbsent(id.group(1), k -> new AtomicInteger()).incrementAndGet();
            }
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        s.start();
        return s;
    }

    static boolean verify(String header, String body) {
        try {
            if (header == null) return false;
            String t = null, v1 = null;
            for (String part : header.split(",")) {
                String[] kv = part.split("=", 2);
                if (kv[0].equals("t")) t = kv[1];
                if (kv[0].equals("v1")) v1 = kv[1];
            }
            if (t == null || v1 == null) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WH_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8))).equals(v1);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers
    static String sh(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IOException(String.join(" ", cmd) + " failed:\n" + out);
        return out;
    }

    static List<String[]> psql(String db, String sql) {
        try {
            Process p = new ProcessBuilder(pgBin + "/psql", "-h", "localhost", "-p", "" + pgPort, "-U", "postgres", "-d", db, "-X", "-q", "-t", "-A", "-F", "|", "-v", "ON_ERROR_STOP=1", "-c", sql)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(PSQL_TIMEOUT.toSeconds(), TimeUnit.SECONDS) || p.exitValue() != 0) throw new IOException("psql: " + out);
            List<String[]> rows = new ArrayList<>();
            for (String line : out.split("\n")) if (!line.isBlank()) rows.add(line.split("\\|", -1));
            return rows;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    static long psqlLong(String db, String sql) {
        return Long.parseLong(psql(db, sql).get(0)[0]);
    }

    // ------------------------------------------------------------------ one worker = one database + one app port
    enum Rank { CREATED, AUTHORIZING, AUTHORIZED, CAPTURING, CAPTURED, PARTIALLY_REFUNDED, REFUNDED, VOIDING, VOIDED, FAILED }

    static final Map<String, Set<String>> NEXT = Map.of(
            "CREATED", Set.of("AUTHORIZING"), "AUTHORIZING", Set.of("AUTHORIZED", "FAILED"), "AUTHORIZED", Set.of("CAPTURING", "VOIDING"),
            "CAPTURING", Set.of("CAPTURED", "FAILED"), "CAPTURED", Set.of("PARTIALLY_REFUNDED", "REFUNDED"),
            "PARTIALLY_REFUNDED", Set.of("PARTIALLY_REFUNDED", "REFUNDED"), "VOIDING", Set.of("VOIDED"), "REFUNDED", Set.of(), "VOIDED", Set.of(), "FAILED", Set.of());

    /** True if {@code to} can be reached from {@code from} by zero or more legal transitions. */
    static boolean reachable(String from, String to) {
        Deque<String> q = new ArrayDeque<>(List.of(from));
        Set<String> seen = new HashSet<>(q);
        while (!q.isEmpty()) {
            String s = q.poll();
            if (s.equals(to)) return true;
            for (String n : NEXT.get(s)) if (seen.add(n)) q.add(n);
        }
        return false;
    }

    record Resp(int status, String body) {}

    /** Everything the client believes, used to check the server against. */
    static final class Pay {
        final int idx; final String merchant; final long amount;
        volatile String id; volatile String ackedStatus; volatile boolean createStarted, createAcked, refundStarted; volatile long refundAmount; volatile boolean refundAcked200;
        Pay(int idx, String merchant, long amount) { this.idx = idx; this.merchant = merchant; this.amount = amount; }
    }

    static final class Worker implements Runnable {
        final int n; final String db; final int port; final int receiverPort; final Random rng;
        final Path logFile;
        volatile boolean gateOpen = false, stopping = false;
        final AtomicInteger tasksDone = new AtomicInteger(), tasksInFlight = new AtomicInteger(), seq = new AtomicInteger();
        final ConcurrentHashMap<String, Long> transfersStarted = new ConcurrentHashMap<>(), transfersAcked = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, Pay> pays = new ConcurrentHashMap<>();
        final AtomicInteger createsDone = new AtomicInteger(), refunds200 = new AtomicInteger(), transfersDone = new AtomicInteger();
        int runs = 0;
        Process app;

        Worker(int n, int receiverPort) {
            this.n = n; this.db = "crash_w" + n; this.port = basePort + n; this.receiverPort = receiverPort;
            this.rng = new Random(seed * 1000 + n);
            this.logFile = Path.of(outDir, "app-w" + n + ".log");
        }

        void fail(String what) {
            String v = "worker " + n + " cycle " + runs + ": " + what;
            violations.add(v);
            System.out.println("VIOLATION " + v);
            abort = true;
        }

        public void run() {
            try {
                sh(pgBin + "/createdb", "-h", "localhost", "-p", "" + pgPort, "-U", "postgres", db);
                Files.deleteIfExists(logFile);
                List<Thread> clients = new ArrayList<>();
                for (int c = 0; c < clientsPerWorker; c++) {
                    int id = c;
                    Thread t = Thread.ofPlatform().daemon().name("client-" + n + "-" + c).start(() -> clientLoop(id));
                    clients.add(t);
                }
                for (int cycle = 1; cycle <= cyclesPerWorker && !abort; cycle++) {
                    runs = cycle;
                    gateOpen = false;
                    boolean startupKill = rng.nextInt(10) == 0;
                    long t0 = System.currentTimeMillis();
                    startApp();
                    if (startupKill) {
                        long wait = 300 + rng.nextInt(4000);
                        Thread.sleep(wait);
                        kill();
                        csv.println(n + "," + cycle + ",startup-kill," + wait + "," + tasksDone.get() + "," + tasksInFlight.get() + ",0,ok");
                        continue;
                    }
                    waitReady();
                    ensureAccounts();
                    int checked = verify(false);
                    gateOpen = true;
                    long wait = killMinMs + rng.nextInt(killMaxMs - killMinMs + 1);
                    Thread.sleep(wait);
                    gateOpen = false;
                    kill();
                    csv.println(n + "," + cycle + ",run," + wait + "," + tasksDone.get() + "," + tasksInFlight.get() + "," + checked + ",ok");
                }
                if (!abort) finalPhase(clients);
                stopping = true;
            } catch (Throwable t) {
                fail("harness error: " + t);
                t.printStackTrace();
            } finally {
                stopping = true;
                if (app != null) app.destroyForcibly();
            }
        }

        // -------------------------------------------------------------- app process
        void startApp() throws IOException {
            ProcessBuilder pb = new ProcessBuilder("java", "-Xmx256m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-jar", jar);
            Map<String, String> e = pb.environment();
            e.put("PORT", "" + port);
            e.put("DATABASE_URL", "jdbc:postgresql://localhost:" + pgPort + "/" + db);
            e.put("DATABASE_USER", "postgres");
            e.put("DB_POOL_SIZE", "10");
            e.put("COUNTERFOIL_API_KEY", API_KEY);
            e.put("COUNTERFOIL_WEBHOOK_SECRET", WH_SECRET);
            e.put("COUNTERFOIL_WEBHOOK_URL", "http://127.0.0.1:" + receiverPort + "/hook");
            e.put("COUNTERFOIL_WEBHOOK_LEASE_SECONDS", "2");
            e.put("COUNTERFOIL_WEBHOOK_BACKOFF_MS", "100");
            e.put("COUNTERFOIL_WEBHOOK_POLL_MS", "200");
            e.put("COUNTERFOIL_WEBHOOK_MAX_ATTEMPTS", "50");
            e.put("COUNTERFOIL_RECOVERY_STALE_SECONDS", "1");
            e.put("COUNTERFOIL_RECOVERY_INTERVAL_MS", "500");
            e.put("COUNTERFOIL_PSP_SEED", "" + (seed + n));
            e.put("COUNTERFOIL_PSP_P_DECLINE", "0.05");
            e.put("COUNTERFOIL_PSP_P_TIMEOUT_BEFORE", "0.08");
            e.put("COUNTERFOIL_PSP_P_TIMEOUT_AFTER", "0.08");
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            app = pb.start();
        }

        void kill() throws InterruptedException {
            app.destroyForcibly();   // SIGKILL: no shutdown hooks, no graceful drain
            app.waitFor();
        }

        void waitReady() throws Exception {
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                if (!app.isAlive()) throw new IllegalStateException("app exited early with " + app.exitValue() + ", see " + logFile);
                try {
                    if (get("/v1/accounts/psp_clearing/balance").status() == 200) return;
                } catch (IOException ignored) { }
                Thread.sleep(100);
            }
            throw new IllegalStateException("app not ready in 120s, see " + logFile);
        }

        void ensureAccounts() throws IOException {
            for (String[] a : new String[][]{{"w-cash", "ASSET"}, {"w-eq", "EQUITY"}}) {
                Resp r = exchange("POST", "/v1/accounts", null, "{\"code\":\"" + a[0] + "\",\"name\":\"" + a[0] + "\",\"type\":\"" + a[1] + "\",\"currency\":\"INR\",\"allow_negative\":true}");
                if (r.status() != 201 && r.status() != 409) fail("could not create account " + a[0] + ": " + r.status() + " " + r.body());
            }
        }

        // -------------------------------------------------------------- HTTP
        Resp get(String path) throws IOException {
            return exchange("GET", path, null, null);
        }

        Resp exchange(String method, String path, String key, String body) throws IOException {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + API_KEY).header("Content-Type", "application/json");
            if (key != null) b.header("Idempotency-Key", key);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            try {
                HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                return new Resp(r.statusCode(), r.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        /** Sends until the server gives an HTTP answer. A dead server (kill, restart, startup) is retried with the same key. */
        Resp sendUntilAnswered(String path, String key, String body) {
            while (true) {
                while (!gateOpen && !abort) sleep(20);
                if (abort) return new Resp(-1, "");
                try {
                    return exchange("POST", path, key, body);
                } catch (IOException e) {
                    sleep(60);
                }
            }
        }

        /** Retries the same request while the server says "processing" (202) or "still in progress" (409 REQUEST_IN_PROGRESS). */
        Resp sendToCompletion(String path, String key, String body) {
            for (int attempt = 0; attempt < 600; attempt++) {
                Resp r = sendUntilAnswered(path, key, body);
                if (r.status() == 202 || (r.status() == 409 && r.body().contains("REQUEST_IN_PROGRESS"))) {
                    sleep(100);
                    continue;
                }
                return r;
            }
            fail("request " + key + " never completed (stuck processing)");
            return new Resp(-1, "");
        }

        static void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        static String field(String json, String name) {
            Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\"([^\"]*)\"|-?[0-9]+)").matcher(json);
            return m.find() ? (m.group(2) != null ? m.group(2) : m.group(1)) : null;
        }

        // -------------------------------------------------------------- client workload
        void clientLoop(int clientId) {
            Random r = new Random(seed * 7919 + n * 131 + clientId);
            while (!stopping && !abort) {
                try {
                    if (r.nextInt(3) == 0) transfer(r); else payment(r);
                    sleep(r.nextInt(15));
                } catch (Throwable t) {
                    fail("client crashed: " + t);
                }
            }
        }

        void transfer(Random r) {
            int i = seq.incrementAndGet();
            String ref = "crash-t-" + i;
            long amount = 1 + r.nextInt(5000);
            String body = "{\"reference\":\"" + ref + "\",\"lines\":[{\"account\":\"w-cash\",\"side\":\"D\",\"amount\":" + amount + "},{\"account\":\"w-eq\",\"side\":\"C\",\"amount\":" + amount + "}]}";
            transfersStarted.put(ref, amount);
            tasksInFlight.incrementAndGet();
            Resp res = sendToCompletion("/v1/journal-entries", "key-" + ref, body);
            tasksInFlight.decrementAndGet();
            if (res.status() == 201) {
                transfersAcked.put(ref, amount);
                transfersDone.incrementAndGet();
                tasksDone.incrementAndGet();
            } else if (res.status() != -1) {
                fail("transfer " + ref + " answered " + res.status() + " " + res.body());
            }
        }

        void payment(Random r) {
            int i = seq.incrementAndGet();
            Pay p = new Pay(i, "w" + n + "m" + r.nextInt(3), 1000 + r.nextInt(90_000));
            pays.put(i, p);
            tasksInFlight.incrementAndGet();
            try {
                p.createStarted = true;
                Resp c = sendToCompletion("/v1/payments", "key-p" + i + "-create",
                        "{\"merchant_id\":\"" + p.merchant + "\",\"amount\":" + p.amount + ",\"currency\":\"INR\"}");
                if (c.status() != 201) { if (c.status() != -1) fail("create answered " + c.status() + " " + c.body()); return; }
                p.id = field(c.body(), "id");
                p.ackedStatus = "CREATED";
                p.createAcked = true;
                createsDone.incrementAndGet();

                Resp a = sendToCompletion("/v1/payments/" + p.id + "/authorize", "key-p" + i + "-auth", null);
                if (!expect200(a, "authorize", p)) return;
                if (!"AUTHORIZED".equals(p.ackedStatus)) { tasksDone.incrementAndGet(); return; }       // declined -> FAILED, pipeline ends

                if (r.nextInt(10) == 0) {                                                                   // some are voided instead of captured
                    Resp v = sendToCompletion("/v1/payments/" + p.id + "/void", "key-p" + i + "-void", null);
                    expect200(v, "void", p);
                    tasksDone.incrementAndGet();
                    return;
                }
                Resp cap = sendToCompletion("/v1/payments/" + p.id + "/capture", "key-p" + i + "-cap", null);
                if (!expect200(cap, "capture", p)) return;
                if ("CAPTURED".equals(p.ackedStatus) && r.nextInt(2) == 0) {
                    long amt = 1 + r.nextInt((int) p.amount);
                    p.refundAmount = amt;
                    p.refundStarted = true;
                    Resp rf = sendToCompletion("/v1/payments/" + p.id + "/refunds", "key-p" + i + "-refund", "{\"amount\":" + amt + "}");
                    if (rf.status() == 409 && rf.body().contains("INSUFFICIENT")) { tasksDone.incrementAndGet(); return; }   // merchant balance too low: refused before the PSP
                    if (!expect200(rf, "refund", p)) return;
                    p.refundAcked200 = true;
                    refunds200.incrementAndGet();
                }
                tasksDone.incrementAndGet();
            } finally {
                tasksInFlight.decrementAndGet();
            }
        }

        boolean expect200(Resp r, String what, Pay p) {
            if (r.status() == -1) return false;
            if (r.status() != 200) { fail(what + " on payment " + p.id + " answered " + r.status() + " " + r.body()); return false; }
            String st = field(r.body(), "status");
            if (st == null) { fail(what + " response without status: " + r.body()); return false; }
            p.ackedStatus = st;
            return true;
        }

        // -------------------------------------------------------------- verification
        /**
         * Checked right after every restart, before any client retry: nothing a client was told succeeded may be missing,
         * nothing may exist that no client ever asked for, and the server's own invariants must hold.
         * @return how many acknowledged facts were checked
         */
        int verify(boolean fin) throws Exception {
            int checked = 0;
            Resp inv = get("/v1/admin/invariants");
            if (inv.status() != 200 || !inv.body().contains("\"ok\":true")) fail("invariants not ok: " + inv.body());
            Resp tb = get("/v1/trial-balance");
            if (!tb.body().contains("\"balanced\":true")) fail("trial balance not balanced: " + tb.body());

            Map<String, Long> entries = new HashMap<>();
            for (String[] row : psql(db, "SELECT e.reference, l.amount FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id WHERE e.reference LIKE 'crash-t-%' AND l.side = 'D'")) {
                if (entries.put(row[0], Long.parseLong(row[1])) != null) fail("transfer " + row[0] + " has more than one entry");
            }
            for (var e : entries.entrySet()) {
                Long asked = transfersStarted.get(e.getKey());
                if (asked == null) fail("phantom entry " + e.getKey() + " that no client asked for");
                else if (!asked.equals(e.getValue())) fail("entry " + e.getKey() + " has amount " + e.getValue() + " but the client asked " + asked);
            }
            for (var a : transfersAcked.entrySet()) {
                checked++;
                if (!entries.containsKey(a.getKey())) fail("LOST ACKED WRITE: transfer " + a.getKey() + " was acknowledged but is not in the ledger");
            }

            Map<String, String[]> payRows = new HashMap<>();
            for (String[] row : psql(db, "SELECT id, amount, status FROM payments")) payRows.put(row[0], row);
            long started = pays.values().stream().filter(p -> p.createStarted).count();
            if (payRows.size() > started) fail("phantom payments: " + payRows.size() + " rows but only " + started + " creates ever sent");
            for (Pay p : pays.values()) {
                if (!p.createAcked) continue;
                checked++;
                String[] row = payRows.get(p.id);
                if (row == null) { fail("LOST ACKED WRITE: payment " + p.id + " was acknowledged but does not exist"); continue; }
                if (Long.parseLong(row[1]) != p.amount) fail("payment " + p.id + " amount changed");
                if (!reachable(p.ackedStatus, row[2])) fail("payment " + p.id + " was acknowledged as " + p.ackedStatus + " but is now " + row[2] + " (not reachable)");
            }
            long refundRows = psqlLong(db, "SELECT count(*) FROM refunds");
            long refundsStarted = pays.values().stream().filter(p -> p.refundStarted).count();
            if (refundRows > refundsStarted) fail("phantom refunds: " + refundRows + " rows but " + refundsStarted + " requested");
            Map<String, Integer> refundsPerPayment = new HashMap<>();
            for (String[] row : psql(db, "SELECT payment_id, count(*) FROM refunds GROUP BY payment_id")) refundsPerPayment.put(row[0], Integer.parseInt(row[1]));
            for (Pay p : pays.values()) {
                if (p.refundAcked200) {
                    checked++;
                    if (refundsPerPayment.getOrDefault(p.id, 0) != 1) fail("LOST ACKED WRITE: refund of " + p.id);
                }
            }
            if (psqlLong(db, "SELECT count(*) FROM (SELECT payment_id, kind FROM payment_ops WHERE kind <> 'REFUND' GROUP BY 1, 2 HAVING count(*) > 1) x") != 0) fail("duplicate payment ops");
            return checked;
        }

        void finalPhase(List<Thread> clients) throws Exception {
            // last incarnation: no more kills. Let clients finish their current task, then check everything for completeness.
            gateOpen = false;
            startApp();
            waitReady();
            ensureAccounts();
            verify(false);
            gateOpen = true;
            Thread.sleep(3000);
            stopping = true;
            for (Thread t : clients) t.join(180_000);
            for (Thread t : clients) if (t.isAlive()) fail("client " + t.getName() + " did not finish: a request is stuck");
            if (abort) return;
            verify(true);
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline && psqlLong(db, "SELECT count(*) FROM payment_ops WHERE state = 'PENDING'") > 0) Thread.sleep(200);
            long pendingOps = psqlLong(db, "SELECT count(*) FROM payment_ops WHERE state = 'PENDING'");
            if (pendingOps != 0) fail(pendingOps + " payment ops are still PENDING after quiescence");
            long inflightStatus = psqlLong(db, "SELECT count(*) FROM payments WHERE status IN ('AUTHORIZING','CAPTURING','VOIDING')");
            if (inflightStatus != 0) fail(inflightStatus + " payments stuck in an in-flight status");

            // exactly-once effects: every request the clients finished produced exactly one effect
            long entries = psqlLong(db, "SELECT count(*) FROM journal_entries WHERE reference LIKE 'crash-t-%'");
            if (entries != transfersDone.get()) fail("transfers: " + transfersDone.get() + " completed but " + entries + " entries exist");
            long payments = psqlLong(db, "SELECT count(*) FROM payments");
            if (payments != createsDone.get()) fail("payments: " + createsDone.get() + " creates completed but " + payments + " rows exist");
            long refundRows = psqlLong(db, "SELECT count(*) FROM refunds");
            if (refundRows != refunds200.get()) fail("refunds: " + refunds200.get() + " completed but " + refundRows + " rows exist");

            // webhooks: drain, then every outbox event must have reached the receiver at least once and none may be dead
            deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline && psqlLong(db, "SELECT count(*) FROM outbox WHERE status = 'PENDING'") > 0) Thread.sleep(250);
            long pending = psqlLong(db, "SELECT count(*) FROM outbox WHERE status = 'PENDING'");
            long dead = psqlLong(db, "SELECT count(*) FROM outbox WHERE status = 'DEAD'");
            if (pending != 0) fail(pending + " outbox events never delivered");
            if (dead != 0) fail(dead + " outbox events are DEAD");
            int missing = 0;
            for (String[] row : psql(db, "SELECT event_id FROM outbox")) if (!received.containsKey(row[0])) missing++;
            if (missing != 0) fail(missing + " outbox events marked delivered but never seen by the receiver");
            Resp inv = get("/v1/admin/invariants");
            if (!inv.body().contains("\"ok\":true")) fail("final invariants not ok: " + inv.body());
            csv.println(n + ",final,final,0," + tasksDone.get() + ",0,0," + (abort ? "FAIL" : "ok"));
        }
    }
}
