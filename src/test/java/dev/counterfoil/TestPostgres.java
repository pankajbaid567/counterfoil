package dev.counterfoil;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * A real PostgreSQL for tests (ADR-014): either the server given by COUNTERFOIL_TEST_DB_URL (CI service
 * container) or a throwaway local cluster started from the PostgreSQL binaries on this machine. No Docker.
 * Each test class gets its own freshly migrated database.
 */
public final class TestPostgres {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static String adminUrl;
    private static String user;
    private static String password;
    private static Path dataDir;
    private static Path pgBin;

    private TestPostgres() {}

    public static synchronized void ensureStarted() {
        if (adminUrl != null) {
            return;
        }
        String env = System.getenv("COUNTERFOIL_TEST_DB_URL");
        if (env != null && !env.isBlank()) {
            adminUrl = env;
            user = System.getenv().getOrDefault("COUNTERFOIL_TEST_DB_USER", "postgres");
            password = System.getenv().getOrDefault("COUNTERFOIL_TEST_DB_PASSWORD", "");
            return;
        }
        try {
            pgBin = findBin();
            dataDir = Files.createTempDirectory("counterfoil-pg");
            Path sock = Files.createTempDirectory("cfsock");
            int port;
            try (ServerSocket s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            run(pgBin.resolve("initdb").toString(), "-D", dataDir.toString(), "-U", "counterfoil", "--auth=trust", "-E", "UTF8", "--no-sync");
            Files.writeString(dataDir.resolve("postgresql.conf"), """
                    listen_addresses = '127.0.0.1'
                    unix_socket_directories = '%s'
                    fsync = off
                    synchronous_commit = off
                    full_page_writes = off
                    max_connections = 200
                    deadlock_timeout = 200ms
                    """.formatted(sock), StandardOpenOption.APPEND);
            run(pgBin.resolve("pg_ctl").toString(), "-D", dataDir.toString(), "-o", "-p " + port, "-w", "-t", "60", "-l", dataDir.resolve("pg.log").toString(), "start");
            Runtime.getRuntime().addShutdownHook(new Thread(TestPostgres::shutdown));
            adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres";
            user = "counterfoil";
            password = "";
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("could not start a local PostgreSQL for tests (set COUNTERFOIL_TEST_DB_URL or PG_BIN): " + e, e);
        }
    }

    private static Path findBin() {
        List<String> candidates = new java.util.ArrayList<>();
        if (System.getenv("PG_BIN") != null) {
            candidates.add(System.getenv("PG_BIN"));
        }
        candidates.addAll(List.of("/opt/homebrew/opt/postgresql@16/bin", "/opt/homebrew/opt/postgresql@15/bin", "/usr/local/opt/postgresql@16/bin",
                "/usr/lib/postgresql/16/bin", "/usr/lib/postgresql/15/bin", "/usr/lib/postgresql/17/bin"));
        for (String c : candidates) {
            if (Files.isExecutable(Path.of(c, "initdb"))) {
                return Path.of(c);
            }
        }
        throw new IllegalStateException("no PostgreSQL binaries found; set PG_BIN or COUNTERFOIL_TEST_DB_URL");
    }

    private static void run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(90, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + " failed:\n" + out);
        }
    }

    private static void shutdown() {
        try {
            run(pgBin.resolve("pg_ctl").toString(), "-D", dataDir.toString(), "-m", "immediate", "-w", "stop");
            try (var walk = Files.walk(dataDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        } catch (Exception ignored) {
            // best effort at JVM exit
        }
    }

    /** Creates a new empty database and applies the Flyway migrations. Returns its JDBC URL. */
    public static String newDatabaseUrl(String prefix) {
        ensureStarted();
        String name = (prefix + "_" + COUNTER.incrementAndGet() + "_" + Long.toHexString(System.nanoTime())).toLowerCase().replaceAll("[^a-z0-9_]", "_");
        try (Connection c = DriverManager.getConnection(adminUrl, user, password); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        String url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + name;
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate();
        return url;
    }

    private static final java.util.List<HikariDataSource> POOLS = new java.util.ArrayList<>();

    /**
     * Tests run one after another and each builds its own TestEnv, so any pool made earlier is finished with. Closing
     * them here keeps the connection count low: leaking them reached 98 of PostgreSQL's default 100 connections and
     * made CI order-dependent.
     */
    public static synchronized DataSource newDataSource(String prefix, int poolSize) {
        for (HikariDataSource old : POOLS) {
            old.close();
        }
        POOLS.clear();
        String url = newDatabaseUrl(prefix);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(1);
        HikariDataSource ds = new HikariDataSource(cfg);
        POOLS.add(ds);
        return ds;
    }

    public static String user() {
        return user;
    }

    public static String password() {
        return password;
    }
}
