package com.equadis.portal.shared.web;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Stops reading a request body once it exceeds a size. The Content-Length header cannot be
 * trusted for this: it may be absent (chunked transfer) or false. Counting the bytes actually
 * read is the only reliable limit.
 */
public final class SizeLimitedInputStream extends FilterInputStream {

    private final long maxBytes;
    private final ErrorCode errorCode;
    private long bytesRead;

    /** @param errorCode the error raised when the limit is passed (its status should be 413) */
    public SizeLimitedInputStream(InputStream input, long maxBytes, ErrorCode errorCode) {
        super(input);
        this.maxBytes = maxBytes;
        this.errorCode = errorCode;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value != -1) {
            count(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        if (count > 0) {
            count(count);
        }
        return count;
    }

    private void count(long bytes) {
        bytesRead += bytes;
        if (bytesRead > maxBytes) {
            throw new PortalException(errorCode, "The request exceeds the maximum size of %,d bytes".formatted(maxBytes));
        }
    }
}
