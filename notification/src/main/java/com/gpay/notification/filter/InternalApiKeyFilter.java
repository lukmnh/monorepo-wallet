package com.gpay.notification.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@Component
@Order(2)
public class InternalApiKeyFilter implements Filter {
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final byte[] internalApiKey;

    public InternalApiKeyFilter(@Value("${internal.api-key}") String internalApiKey) {
        // docker compose substitutes "" for a missing variable; an empty key would accept an empty header
        if (internalApiKey == null || internalApiKey.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_KEY must be set");
        }
        this.internalApiKey = internalApiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (httpRequest.getRequestURI().startsWith("/api/v1/internal/")) {
            String apiKey = httpRequest.getHeader(INTERNAL_API_KEY_HEADER);
            // Constant-time compare: response time must not leak how many leading bytes matched
            if (apiKey == null || !MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8), internalApiKey)) {
                log.warn("Unauthorized internal API access to notification-service from IP={}", httpRequest.getRemoteAddr());
                httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
                httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
                httpResponse.getWriter().write("{\"success\":false,\"message\":\"Forbidden\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
