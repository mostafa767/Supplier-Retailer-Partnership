package com.equadis.portal.importing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * @param storageDirectory where uploaded files are kept (object storage such as S3 in production)
 * @param maxRows          maximum offers per file or request (R3: up to 100,000)
 * @param chunkSize        offers written per transaction: big enough for throughput, small
 *                         enough to keep transactions and locks short and to checkpoint often
 * @param lease            how long a claimed job is reserved; extended after every chunk
 * @param maxAttempts      a job failing technically this many times is marked FAILED
 * @param maxRequestSize   largest file or JSON request accepted, counted while reading
 */
@ConfigurationProperties("portal.imports")
public record ImportProperties(@DefaultValue("./data/imports") String storageDirectory,
                               @DefaultValue("100000") int maxRows,
                               @DefaultValue("1000") int chunkSize,
                               @DefaultValue("PT1M") Duration lease,
                               @DefaultValue("3") int maxAttempts,
                               @DefaultValue("50MB") DataSize maxRequestSize) {
}
