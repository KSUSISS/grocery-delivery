package ru.komissarov.grocerydelivery;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real temporary PostgreSQL shared by test contexts; no local server or Docker required. */
public abstract class PostgresTestSupport {
    private static final EmbeddedPostgres POSTGRES = start();
    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.builder().setPort(0).start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { pg.close(); } catch (Exception ignored) { /* JVM is shutting down. */ }
            }));
            return pg;
        } catch (Exception e) { throw new IllegalStateException("Cannot start test PostgreSQL", e); }
    }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }
}
