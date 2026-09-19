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
  private final byte[] adminKey;
  private final byte[] readerKey;

  public OperatorApiKeyInterceptor(
      @Value("${northstar.security.operator-api-key:}") String adminKey,
      @Value("${northstar.security.operator-read-api-key:}") String readerKey) {
    this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
    this.readerKey = readerKey.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String supplied = request.getHeader("X-NorthStar-Operator-Key");
    byte[] candidate = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
    if (matches(adminKey, candidate)) return true;
    if (matches(readerKey, candidate)) {
      if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) return true;
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      return false;
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    return false;
  }

  private static boolean matches(byte[] expected, byte[] candidate) {
    return expected.length > 0 && MessageDigest.isEqual(expected, candidate);
  }
}
