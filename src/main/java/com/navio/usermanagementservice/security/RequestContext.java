package com.navio.usermanagementservice.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Captures the client attributes recorded on audit rows.
 *
 * <p><strong>On the client IP:</strong> this reads {@code getRemoteAddr()} and
 * never parses {@code X-Forwarded-For} by hand. Any client can send an
 * {@code X-Forwarded-For} header, so trusting it directly would let an attacker
 * write whatever address they like into the security audit log — poisoning the
 * one record an investigation depends on.
 *
 * <p>Spring's {@code ForwardedHeaderFilter} resolves the real client address
 * from proxy headers only when {@code server.forward-headers-strategy=FRAMEWORK}
 * is set <em>and</em> the request arrives through a trusted proxy. That is
 * configured in {@code application.yml}, so by the time this class runs,
 * {@code getRemoteAddr()} is already the vetted value.
 */
@Component
public class RequestContext {

    /** Matches the {@code iam.audit_log.ip_address} column width. */
    private static final int MAX_IP_LENGTH = 45;

    /** Bounds an attacker-controlled header so it cannot bloat the audit table. */
    private static final int MAX_USER_AGENT_LENGTH = 512;

    /** @return the resolved client IP, or empty outside a request scope. */
    public Optional<String> clientIp() {
        return currentRequest()
                .map(HttpServletRequest::getRemoteAddr)
                .filter(ip -> !ip.isBlank())
                .map(ip -> truncate(ip, MAX_IP_LENGTH));
    }

    /** @return the client user agent, truncated to a safe length. */
    public Optional<String> userAgent() {
        return currentRequest()
                .map(request -> request.getHeader(HttpHeaders.USER_AGENT))
                .filter(agent -> !agent.isBlank())
                .map(agent -> truncate(agent, MAX_USER_AGENT_LENGTH));
    }

    private Optional<HttpServletRequest> currentRequest() {
        // Async tasks and the outbox relay run outside a request; audit rows
        // written there simply carry no client attributes.
        return Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                .filter(ServletRequestAttributes.class::isInstance)
                .map(ServletRequestAttributes.class::cast)
                .map(ServletRequestAttributes::getRequest);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
