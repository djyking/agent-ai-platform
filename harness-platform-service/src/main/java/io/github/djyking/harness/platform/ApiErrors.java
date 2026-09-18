package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

@RestControllerAdvice
public final class ApiErrors {
  @ExceptionHandler(Exception.class)
  public ResponseEntity<JsonNode> error(Exception ex, HttpServletRequest request) {
    int status;
    String code;
    if (ex instanceof ApiFailure f) {
      status = f.status;
      code = f.code;
    } else if (ex instanceof RunStore.RevisionConflict) {
      status = 412;
      code = "PRECONDITION_FAILED";
    } else if (ex instanceof RunStore.Conflict) {
      status = 409;
      code = "INVALID_STATE";
    } else if (ex instanceof RunStore.NotFound) {
      status = 404;
      code = "NOT_FOUND";
    } else if (ex instanceof Contracts.InvocationException) {
      status = 403;
      code = "FORBIDDEN";
    } else if (ex instanceof org.springframework.web.HttpRequestMethodNotSupportedException) {
      status = 405;
      code = "METHOD_NOT_ALLOWED";
    } else if (ex
            instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
        || ex instanceof IllegalArgumentException) {
      status = 400;
      code = "INVALID_REQUEST";
    } else if (ex instanceof org.springframework.web.servlet.resource.NoResourceFoundException) {
      status = 404;
      code = "NOT_FOUND";
    } else if (ex instanceof io.github.djyking.harness.storage.jdbc.JdbcStorageException) {
      status = 503;
      code = "TEMPORARILY_UNAVAILABLE";
    } else {
      status = 500;
      code = "INTERNAL_ERROR";
    }
    String id = (String) request.getAttribute("requestId");
    if (id == null) id = "req_" + UUID.randomUUID().toString().replace("-", "");
    var error =
        Json.object()
            .put("code", code)
            .put("message", code.replace('_', ' '))
            .put("requestId", id)
            .put("retryDisposition", status >= 500 || status == 429 ? "SAME_KEY_ONLY" : "NEVER");
    var body = Json.object();
    body.set("error", error);
    var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
    if (status == 401) response.header("WWW-Authenticate", "Bearer");
    if (ex instanceof org.springframework.web.HttpRequestMethodNotSupportedException method
        && method.getSupportedHttpMethods() != null)
      response.allow(method.getSupportedHttpMethods().toArray(HttpMethod[]::new));
    if (status >= 500 || status == 429) response.header("Retry-After", "2");
    return response.body(body);
  }

  @Component
  public static final class RequestHeaders extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String id = "req_" + UUID.randomUUID().toString().replace("-", "");
      request.setAttribute("requestId", id);
      response.setHeader("X-Request-Id", id);
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("X-Content-Type-Options", "nosniff");
      chain.doFilter(request, response);
    }
  }
}
