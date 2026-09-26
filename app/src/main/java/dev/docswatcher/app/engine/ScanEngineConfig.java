package dev.docswatcher.app.engine;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.config.ScanIsolationProperties;
import dev.docswatcher.app.config.ScanLimitsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** The one {@link ScanEngine}: in a process of its own per scan, or in this JVM. */
@Configuration
@ConditionalOnProperty(name = "docswatcher.engine.enabled", havingValue = "true", matchIfMissing = true)
public class ScanEngineConfig {

  private static final Logger log = LoggerFactory.getLogger(ScanEngineConfig.class);

  @Bean
  ScanEngine scanEngine(AppProperties properties, ObjectMapper mapper, ScanLimitsProperties limits, ScanIsolationProperties isolation) {
    EngineScanEngine local = new EngineScanEngine(properties, mapper, limits);
    if (isolation.isolationOrDefault() == ScanIsolationProperties.Isolation.IN_PROCESS) {
      log.info("Scans run inside the web server (docswatcher.scan.isolation: in-process)");
      return local;
    }
    ProcessScanEngine process = new ProcessScanEngine(local,
        new ProcessScanEngine.Settings(isolation.heapMb(), isolation.timeout(local.limits()), isolation.jvmOptions()));
    String problem = process.probe();
    if (problem != null) {
      // Failing every scan would be worse than running them here, so they run here, loudly.
      log.error("A scan process cannot start here ({}). Scans run inside the web server until this is fixed; "
          + "set docswatcher.scan.isolation: in-process to accept that and silence this message", problem);
      return local;
    }
    return process;
  }
}
