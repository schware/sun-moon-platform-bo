package com.sunmoon.bo;

/**
 * BO's configuration. Deliberately smaller than the runtime platform's:
 * BO serves one HTTP listener and nothing else, so there is no API port
 * and no socket port to name here.
 *
 * <p>This is also the reason the kernel has no configuration class of its
 * own — a shared {@code RuntimeConfig} would have to know that BO exists
 * (docs/adr/0014).
 */
public final class BoConfig {

    private final int port;
    private final int workerThreads;
    private final boolean secureCookies;

    private BoConfig(int port, int workerThreads, boolean secureCookies) {
        this.port = port;
        this.workerThreads = workerThreads;
        this.secureCookies = secureCookies;
    }

    public static BoConfig fromEnv() {
        var env = System.getenv();
        // 8080 is BO's slot in the family-wide port scheme (docs/adr/0013).
        // PORT is honoured too, for hosts that inject it and expect the
        // published service to bind exactly that.
        String defaultPort = env.getOrDefault("PORT", "8080");
        return new BoConfig(
                Integer.parseInt(env.getOrDefault("BO_PORT", defaultPort)),
                Integer.parseInt(env.getOrDefault("WORKER_THREADS",
                        String.valueOf(Runtime.getRuntime().availableProcessors() * 4))),
                Boolean.parseBoolean(env.getOrDefault("COOKIE_SECURE", "false")));
    }

    public int port() {
        return port;
    }

    /**
     * Size of the pool endpoints run on (docs/adr/0010). Blocking DB work is
     * why it exists, so the useful ceiling is related to
     * {@code POSTGRES_POOL_SIZE} — threads beyond that just queue on
     * HikariCP instead of on the executor.
     */
    public int workerThreads() {
        return workerThreads;
    }

    /**
     * Adds {@code Secure} to the session cookie. Off by default because
     * local development is plain HTTP and a {@code Secure} cookie would
     * simply never be sent; must be on wherever BO is served over TLS
     * (docs/adr/0004).
     */
    public boolean secureCookies() {
        return secureCookies;
    }
}
