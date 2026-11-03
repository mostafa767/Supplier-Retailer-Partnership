package com.equadis.portal.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Middleware #1 (runs first, around everything): request tracing.
 *
 * <ol>
 *     <li>Takes the caller's {@code X-Correlation-Id} (so a supplier system can trace its own
 *         call through our logs) or generates one;</li>
 *     <li>puts it in the logging context (MDC) - every log line of this request carries it;</li>
 *     <li>returns it in the response header and, on errors, in the error body;</li>
 *     <li>logs one summary line per request: method, path, status, duration.</li>
 * </ol>
 *
 * <p>The incoming value is validated: an attacker must not be able to inject line breaks or
 * huge strings into our logs through this header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTracingFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(RequestTracingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = correlationIdOf(request);
        long startNanos = System.nanoTime();
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(), durationMillis);
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Health probes run every few seconds; tracing them only adds noise.
        return request.getRequestURI().startsWith("/actuator");
    }

    private static String correlationIdOf(HttpServletRequest request) {
        String provided = request.getHeader(CORRELATION_ID_HEADER);
        if (provided != null && SAFE_CORRELATION_ID.matcher(provided).matches()) {
            return provided;
        }
        return UUID.randomUUID().toString();
    }
}
