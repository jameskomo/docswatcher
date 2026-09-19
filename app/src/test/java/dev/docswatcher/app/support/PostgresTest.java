package dev.docswatcher.app.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres container for the whole test JVM. It is started once in a static initializer and
 * never stopped by JUnit, because the cached Spring context outlives any single test class.
 * Testcontainers' Ryuk reaper removes it when the JVM exits.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestBeans.class)
public abstract class PostgresTest {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  static {
    POSTGRES.start();
  }
}
