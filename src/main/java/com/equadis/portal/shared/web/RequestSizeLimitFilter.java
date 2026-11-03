package com.equadis.portal.shared.web;

import com.equadis.portal.shared.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Middleware #2: limits the size of request bodies (1 MB by default).
 *
 * <p>Without it, one request with a huge JSON body would be read entirely into memory, and a
 * few of them could exhaust the server. The limit counts the bytes actually read, so a missing
 * or false Content-Length header does not get around it. Going over it is answered with
 * 413 REQUEST_TOO_LARGE, in the usual error format.
 *
 * <p>Imports are left out: they stream up to 50 MB and enforce that limit themselves.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final String IMPORTS_PATH = "/api/imports";

    private final long maxBytes;

    public RequestSizeLimitFilter(@Value("${portal.web.max-request-size:1MB}") DataSize maxRequestSize) {
        this.maxBytes = maxRequestSize.toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new SizeLimitedRequest(request, maxBytes), response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(IMPORTS_PATH);
    }

    /** The same request, whose body stops being readable past the limit. */
    private static final class SizeLimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream limitedBody;

        SizeLimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limitedBody == null) {
                limitedBody = new LimitedServletInputStream(super.getInputStream(), maxBytes);
            }
            return limitedBody;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            Charset charset = getCharacterEncoding() == null ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream original;
        private final InputStream limited;

        LimitedServletInputStream(ServletInputStream original, long maxBytes) {
            this.original = original;
            this.limited = new SizeLimitedInputStream(original, maxBytes, ErrorCode.REQUEST_TOO_LARGE);
        }

        @Override
        public int read() throws IOException {
            return limited.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return limited.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return original.isFinished();
        }

        @Override
        public boolean isReady() {
            return original.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            original.setReadListener(readListener);
        }
    }
}
