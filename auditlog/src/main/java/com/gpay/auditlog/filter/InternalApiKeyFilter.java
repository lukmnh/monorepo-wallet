package com.gpay.auditlog.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@Order(2)
public class InternalApiKeyFilter implements Filter {
    @Value("${internal.api-key}")
    private String internalApiKey;

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
        if (apiKey == null || !apiKey.equals(internalApiKey)) {
            log.warn("Unauthorized access to audit-service from IP={}", httpReq.getRemoteAddr());
            httpRes.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpRes.getWriter().write("{\"success\":false,\"message\":\"Forbidden\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
