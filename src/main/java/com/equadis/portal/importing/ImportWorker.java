package com.equadis.portal.importing;

import com.equadis.portal.shared.web.RequestTracingFilter;
import com.equadis.portal.shared.work.ClaimedWork;
import com.equadis.portal.shared.work.WorkClaimRepository;
import com.equadis.portal.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Picks the next pending import (any marketplace) and processes it under its marketplace.
 *
 * <p>Failures are classified:
 * <ul>
 *     <li><b>line problems</b> never fail the job - they are rows in the error report;</li>
 *     <li><b>technical problems</b> (database down, worker crash...) are retried up to
 *         {@code maxAttempts}; the job then becomes FAILED and an error is logged (alerting).
 *         The supplier sees a generic reason - internals stay in our logs.</li>
 * </ul>
 */
@Component
public class ImportWorker {

    private static final Logger log = LoggerFactory.getLogger(ImportWorker.class);

    private final WorkClaimRepository workClaimRepository;
    private final ImportJobProcessor processor;
    private final ImportJobRepository jobRepository;
    private final ImportProperties properties;
    private final Clock clock;

    ImportWorker(WorkClaimRepository workClaimRepository, ImportJobProcessor processor, ImportJobRepository jobRepository,
                 ImportProperties properties, Clock clock) {
        this.workClaimRepository = workClaimRepository;
        this.processor = processor;
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return true if a job was claimed (the caller may immediately ask for the next one) */
    public boolean processNext() {
        List<ClaimedWork> claimed = workClaimRepository.claimImportJob(clock.instant(), properties.lease());
        if (claimed.isEmpty()) {
            return false;
        }
        ClaimedWork work = claimed.get(0);
        TenantContext.runAs(work.marketplaceId(), () -> run(work));
        return true;
    }

    private void run(ClaimedWork work) {
        MDC.put(RequestTracingFilter.CORRELATION_ID_MDC_KEY, "import-" + work.id());
        try {
            if (work.attempts() > properties.maxAttempts()) {
                giveUp(work, new IllegalStateException("too many attempts (worker crashes)"));
                return;
            }
            log.info("Processing import {} (attempt {}/{})", work.id(), work.attempts(), properties.maxAttempts());
            processor.process(work.id());
        } catch (RuntimeException failure) {
            if (work.attempts() >= properties.maxAttempts()) {
                giveUp(work, failure);
            } else {
                log.warn("Import {} failed (attempt {}/{}), will retry", work.id(), work.attempts(), properties.maxAttempts(), failure);
                jobRepository.markAttemptFailed(work.id(), false, publicReason(work), clock.instant());
            }
        } finally {
            MDC.remove(RequestTracingFilter.CORRELATION_ID_MDC_KEY);
        }
    }

    private void giveUp(ClaimedWork work, RuntimeException failure) {
        log.error("Import {} FAILED after {} attempt(s)", work.id(), work.attempts(), failure);
        jobRepository.markAttemptFailed(work.id(), true, publicReason(work), clock.instant());
    }

    /** What the supplier sees: actionable, without internal details. */
    private static String publicReason(ClaimedWork work) {
        return "Technical error while processing the file (support reference: import-%s). Lines already reported remain valid; you may upload the same file again."
                .formatted(work.id());
    }
}
