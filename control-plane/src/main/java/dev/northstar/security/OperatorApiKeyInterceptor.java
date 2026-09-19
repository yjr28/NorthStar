package dev.northstar.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class OperatorApiKeyInterceptor implements HandlerInterceptor {
  private final byte[] expected;

  public OperatorApiKeyInterceptor(@Value("${northstar.security.operator-api-key:}") String apiKey) {
    this.expected = apiKey.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String supplied = request.getHeader("X-NorthStar-Operator-Key");
    byte[] candidate = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
    if (expected.length == 0 || !MessageDigest.isEqual(expected, candidate)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return false;
    }
    return true;
  }
}
