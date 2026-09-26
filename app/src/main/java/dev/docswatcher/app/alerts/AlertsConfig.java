package dev.docswatcher.app.alerts;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Alerts before the date: the daily job needs a scheduler, and the mailer its settings.
 * The job's time is {@code docswatcher.alerts.cron} (UTC); {@code "-"} turns it off.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(BrevoProperties.class)
public class AlertsConfig {}
