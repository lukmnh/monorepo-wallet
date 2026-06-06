package com.gpay.wallets.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final SecretKey secretKey;

    public JwtAuthFilter(@Value("${jwt.secret}") String secret){
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                Claims claims = Jwts.parser().verifyWith(secretKey).build()
                        .parseSignedClaims(token).getPayload();

                String userId = claims.getSubject();
                MDC.put("userId", userId);

                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null,
                        java.util.List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);

            } catch (ExpiredJwtException e) {
                log.warn("Token expired: {}", e.getMessage());
                sendUnauthorized(response, "Token has expired");
                return;
            } catch (JwtException e) {
                log.warn("Token invalid: {}", e.getMessage());
                sendUnauthorized(response, "Invalid token");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
