package com.gpay.auditlog.filter;

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
    private final byte[] internalApiKey;

    public InternalApiKeyFilter(@Value("${internal.api-key}") String internalApiKey) {
        // docker compose substitutes "" for a missing variable; an empty key would accept an empty header
        if (internalApiKey == null || internalApiKey.isBlank()) {
            throw new IllegalStateException("INTERNAL_API_KEY must be set");
        }
        this.internalApiKey = internalApiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;

        String path = httpReq.getRequestURI();
        if (path.equals("/actuator/health")) {
            chain.doFilter(request, response);
            return;
        }

        String apiKey = httpReq.getHeader("X-Internal-Api-Key");
        // Constant-time compare: response time must not leak how many leading bytes matched
        if (apiKey == null || !MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8), internalApiKey)) {
            log.warn("Unauthorized access to audit-service from IP={}", httpReq.getRemoteAddr());
            httpRes.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpRes.setContentType(MediaType.APPLICATION_JSON_VALUE);
            httpRes.getWriter().write("{\"success\":false,\"message\":\"Forbidden\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
