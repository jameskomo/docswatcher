package dev.docswatcher.app.earlyaccess;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a real local HTTP server standing in for the notify/ worker. */
class EarlyAccessNotifierTest {

  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final List<String> auth = new CopyOnWriteArrayList<>();
  private final AtomicInteger failuresLeft = new AtomicInteger();
  private HttpServer server;
  private String url;

  private static final EarlyAccessNotifier.Lead LEAD =
      new EarlyAccessNotifier.Lead("lead@acme.io", "Acme", "1 to 10", "Stripe", "Alerts", "Hi", 1);

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(failuresLeft.getAndDecrement() > 0 ? 500 : 202, -1);
      exchange.close();
    });
    server.start();
    url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void sendsTheLeadWithTheToken() {
    assertThat(new EarlyAccessNotifier(url, "s3cret\n").send(LEAD)).isTrue();
    assertThat(auth).containsExactly("Bearer s3cret");
    assertThat(bodies.get(0)).contains("\"email\":\"lead@acme.io\"", "\"company\":\"Acme\"", "\"requests\":1");
  }

  @Test
  void triesAgainWhenTheWorkerFails() {
    failuresLeft.set(1);
    assertThat(new EarlyAccessNotifier(url, "t").send(LEAD)).isTrue();
    assertThat(bodies).hasSize(2);
  }

  @Test
  void unconfiguredSendsNothing() {
    new EarlyAccessNotifier("", "").tell(LEAD);
    new EarlyAccessNotifier(url, "").tell(LEAD);
    assertThat(bodies).isEmpty();
  }
}
