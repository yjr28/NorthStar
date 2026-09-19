package dev.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import dev.northstar.observability.NorthStarMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class NorthStarMetricsTest {
  @Test
  void recordsLifecycleCountersWithStablePrometheusNames() {
    var registry = new SimpleMeterRegistry();
    var metrics = new NorthStarMetrics(registry);

    metrics.telemetryAccepted();
    metrics.commandQueued();
    metrics.commandsLeased(3);
    metrics.commandAcknowledged();
    metrics.commandAckConflict();
    metrics.agentAuthFailure();

    assertThat(registry.get("northstar.telemetry.accepted").counter().count()).isEqualTo(1);
    assertThat(registry.get("northstar.commands.queued").counter().count()).isEqualTo(1);
    assertThat(registry.get("northstar.commands.leased").counter().count()).isEqualTo(3);
    assertThat(registry.get("northstar.commands.acknowledged").counter().count()).isEqualTo(1);
    assertThat(registry.get("northstar.commands.ack.conflicts").counter().count()).isEqualTo(1);
    assertThat(registry.get("northstar.agent.auth.failures").counter().count()).isEqualTo(1);
  }
}
