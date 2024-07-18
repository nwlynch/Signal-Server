package org.whispersystems.textsecuregcm.workers;

import io.dropwizard.core.Application;
import io.dropwizard.core.cli.ServerCommand;
import io.dropwizard.core.server.DefaultServerFactory;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.jetty.HttpsConnectorFactory;
import io.dropwizard.lifecycle.Managed;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.WhisperServerConfiguration;
import org.whispersystems.textsecuregcm.metrics.MetricsUtil;
import org.whispersystems.textsecuregcm.scheduler.JobScheduler;
import org.whispersystems.textsecuregcm.util.logging.UncaughtExceptionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ProcessScheduledJobsServiceCommand extends ServerCommand<WhisperServerConfiguration> {

  private final String name;
  private final JobSchedulerFactory jobSchedulerFactory;

  private static final String FIXED_DELAY_SECONDS_ARGUMENT = "fixedDelay";
  private static final int DEFAULT_FIXED_DELAY_SECONDS = 60;

  private static final Logger log = LoggerFactory.getLogger(ProcessScheduledJobsServiceCommand.class);

  private static class ScheduledJobProcessor implements Managed {

    private final JobScheduler jobScheduler;

    private final ScheduledExecutorService scheduledExecutorService;
    private final int fixedDelaySeconds;

    private ScheduledFuture<?> processJobsFuture;

    private ScheduledJobProcessor(final JobScheduler jobScheduler,
        final ScheduledExecutorService scheduledExecutorService,
        final int fixedDelaySeconds) {

      this.jobScheduler = jobScheduler;
      this.scheduledExecutorService = scheduledExecutorService;
      this.fixedDelaySeconds = fixedDelaySeconds;
    }

    @Override
    public void start() {
      processJobsFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
        try {
          jobScheduler.processAvailableJobs().join();
        } catch (final Exception e) {
          log.warn("Failed to process available jobs for scheduler: {}", jobScheduler.getSchedulerName(), e);
        }
      }, 0, fixedDelaySeconds, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
      if (processJobsFuture != null) {
        processJobsFuture.cancel(false);
      }

      processJobsFuture = null;
    }
  }

  public ProcessScheduledJobsServiceCommand(final String name,
      final String description,
      final JobSchedulerFactory jobSchedulerFactory) {

    super(new Application<>() {
            @Override
            public void run(WhisperServerConfiguration configuration, Environment environment) {
            }
          }, name,
        description);

    this.name = name;
    this.jobSchedulerFactory = jobSchedulerFactory;
  }

  @Override
  public void configure(final Subparser subparser) {
    super.configure(subparser);

    subparser.addArgument("--fixed-delay")
        .type(Integer.class)
        .dest(FIXED_DELAY_SECONDS_ARGUMENT)
        .setDefault(DEFAULT_FIXED_DELAY_SECONDS)
        .help("The delay, in seconds, between queries for jobs to process");
  }

  @Override
  protected void run(final Environment environment,
      final Namespace namespace,
      final WhisperServerConfiguration configuration)
      throws Exception {

    UncaughtExceptionHandler.register();

    final CommandDependencies commandDependencies = CommandDependencies.build(name, environment, configuration);

    final int fixedDelaySeconds = namespace.getInt(FIXED_DELAY_SECONDS_ARGUMENT);

    MetricsUtil.configureRegistries(configuration, environment, commandDependencies.dynamicConfigurationManager());

    // Even though we're not actually serving traffic, `ServerCommand` subclasses need a valid server configuration, and
    // that means they need to be able to decrypt the TLS keystore.
    if (configuration.getServerFactory() instanceof DefaultServerFactory defaultServerFactory) {
      defaultServerFactory.getApplicationConnectors()
          .forEach(connectorFactory -> {
            if (connectorFactory instanceof HttpsConnectorFactory h) {
              h.setKeyStorePassword(configuration.getTlsKeyStoreConfiguration().password().value());
            }
          });
    }

    final ScheduledExecutorService scheduledExecutorService =
        environment.lifecycle().scheduledExecutorService("scheduled-job-processor-%d", false)
            .build();

    final JobScheduler jobScheduler = jobSchedulerFactory.buildJobScheduler(commandDependencies, configuration);

    environment.lifecycle().manage(new ScheduledJobProcessor(jobScheduler, scheduledExecutorService, fixedDelaySeconds));

    MetricsUtil.registerSystemResourceMetrics(environment);

    super.run(environment, namespace, configuration);
  }
}
