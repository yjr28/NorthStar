package dev.northstar.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class OperatorSecurityConfiguration implements WebMvcConfigurer {
  private final OperatorApiKeyInterceptor interceptor;

  public OperatorSecurityConfiguration(OperatorApiKeyInterceptor interceptor) {
    this.interceptor = interceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(interceptor)
        .addPathPatterns(
            "/api/v1/hosts",
            "/api/v1/hosts/*",
            "/api/v1/hosts/*/telemetry",
            "/api/v1/hosts/*/status",
            "/api/v1/commands",
            "/api/v1/commands/*")
        .excludePathPatterns(
            "/api/v1/hosts/*/heartbeat",
            "/api/v1/hosts/*/credentials/rotate",
            "/api/v1/hosts/*/commands/lease",
            "/api/v1/commands/*/ack");
  }
}
