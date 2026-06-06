package com.gpay.wallets.filter;

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
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    @Value("${internal.api-key}")
    private String internalApiKey;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (httpRequest.getRequestURI().startsWith("/api/v1/internal/")) {
            String apiKey = httpRequest.getHeader(INTERNAL_API_KEY_HEADER);
            if (apiKey == null || !apiKey.equals(internalApiKey)) {
                log.warn("Unauthorized internal API access from IP={}", httpRequest.getRemoteAddr());
                httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
                httpResponse.getWriter().write("{\"success\":false,\"message\":\"Forbidden\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
