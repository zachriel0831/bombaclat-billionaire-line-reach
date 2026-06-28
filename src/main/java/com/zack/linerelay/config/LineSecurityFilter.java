package com.zack.linerelay.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class LineSecurityFilter extends OncePerRequestFilter {

    private final LineSecurityProperties security;
    private final InMemoryRateLimiter rateLimiter;

    public LineSecurityFilter(LineSecurityProperties security, InMemoryRateLimiter rateLimiter) {
        this.security = security;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!security.enabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isAdminPath(request)) {
            protectAdminRequest(request, response, filterChain);
            return;
        }

        if (isWebhookWrite(request)) {
            protectWebhookRate(request, response, filterChain);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void protectAdminRequest(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws IOException, ServletException {
        if (!security.hasAdminKeys()) {
            writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "admin_security_not_configured");
            return;
        }
        String key = request.getHeader(security.adminApiKeyHeader());
        if (!security.validAdminKey(key)) {
            writeError(response, HttpStatus.UNAUTHORIZED, "invalid_admin_api_key");
            return;
        }
        if (!rateLimiter.tryAcquire("admin:" + key, security.adminRateLimitPerMinute())) {
            writeError(response, HttpStatus.TOO_MANY_REQUESTS, "admin_rate_limited");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void protectWebhookRate(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws IOException, ServletException {
        String bucket = "webhook:" + request.getRemoteAddr();
        if (!rateLimiter.tryAcquire(bucket, security.webhookRateLimitPerMinute())) {
            writeError(response, HttpStatus.TOO_MANY_REQUESTS, "webhook_rate_limited");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isAdminPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/admin") || path.startsWith("/admin/");
    }

    private static boolean isWebhookWrite(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && "/webhook".equals(request.getRequestURI());
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String error)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}

