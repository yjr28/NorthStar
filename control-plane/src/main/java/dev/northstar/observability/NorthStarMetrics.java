package dev.northstar.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class NorthStarMetrics {
  private final Counter telemetryAccepted;
  private final Counter commandsQueued;
  private final Counter commandsLeased;
  private final Counter commandsAcknowledged;
  private final Counter commandAckConflicts;
  private final Counter agentAuthFailures;

  public NorthStarMetrics(MeterRegistry registry) {
    telemetryAccepted = counter(registry, "northstar.telemetry.accepted", "Telemetry samples accepted by the control plane");
    commandsQueued = counter(registry, "northstar.commands.queued", "Commands newly queued for delivery");
    commandsLeased = counter(registry, "northstar.commands.leased", "Commands leased to agents, including redelivery after lease expiry");
    commandsAcknowledged = counter(registry, "northstar.commands.acknowledged", "Commands successfully acknowledged by agents");
    commandAckConflicts = counter(registry, "northstar.commands.ack.conflicts", "Command acknowledgements rejected because lease state did not match");
    agentAuthFailures = counter(registry, "northstar.agent.auth.failures", "Rejected agent requests with invalid credentials");
  }

  private static Counter counter(MeterRegistry registry, String name, String description) {
    return Counter.builder(name).description(description).register(registry);
  }

  public void telemetryAccepted() { telemetryAccepted.increment(); }
  public void commandQueued() { commandsQueued.increment(); }
  public void commandsLeased(int count) { commandsLeased.increment(count); }
  public void commandAcknowledged() { commandsAcknowledged.increment(); }
  public void commandAckConflict() { commandAckConflicts.increment(); }
  public void agentAuthFailure() { agentAuthFailures.increment(); }
}
