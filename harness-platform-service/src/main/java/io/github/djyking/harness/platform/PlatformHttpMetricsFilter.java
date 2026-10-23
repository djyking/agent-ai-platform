package io.github.djyking.harness.platform;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** A BFF internal forward is part of the original HTTP request, not another observation. */
@Component
public final class PlatformHttpMetricsFilter extends OncePerRequestFilter {
  private final PlatformHttpMetrics metrics;

  public PlatformHttpMetricsFilter(OperationsService operations) {
    metrics = operations.httpMetrics();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var route = PlatformHttpMetrics.route(request.getRequestURI());
    if (route == null) {
      chain.doFilter(request, response);
      return;
    }
    long began = System.nanoTime();
    boolean completed = false;
    try {
      chain.doFilter(request, response);
      completed = true;
    } finally {
      metrics.observe(
          route,
          request.getMethod(),
          completed ? response.getStatus() : 500,
          System.nanoTime() - began);
    }
  }
}
