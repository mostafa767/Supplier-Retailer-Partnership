package com.equadis.portal.shared.error;

import com.equadis.portal.shared.validation.Violation;
import com.equadis.portal.shared.web.RequestTracingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns every exception into one consistent JSON error (RFC 9457 "Problem Details").
 *
 * <p>Example response:
 * <pre>{@code
 * HTTP/1.1 422
 * {
 *   "type": "urn:equadis:portal:error:OFFER_NOT_EDITABLE",
 *   "title": "Offer not editable",
 *   "status": 422,
 *   "detail": "Offer SUMMER-01 is PUBLISHED; only DRAFT offers can be edited",
 *   "instance": "/api/offers/6f1c...",
 *   "code": "OFFER_NOT_EDITABLE",
 *   "businessRule": "BR-08",
 *   "correlationId": "b0b5f2d7-...",
 *   "timestamp": "2026-11-02T09:15:00Z"
 * }
 * }</pre>
 *
 * <p>Debuggability rules:
 * <ul>
 *     <li>every response carries the {@code correlationId}, which is also on every log line of
 *         that request - one search finds the whole story;</li>
 *     <li>expected failures (4xx) are logged once at WARN, without stack trace (they are not
 *         bugs);</li>
 *     <li>unexpected failures (5xx) are logged at ERROR with the full stack trace, and the
 *         caller gets a generic message - internals (SQL, class names) never leak out.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String PROBLEM_TYPE_PREFIX = "urn:equadis:portal:error:";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final DatabaseConstraintTranslator constraintTranslator;
    private final Clock clock;

    public GlobalExceptionHandler(DatabaseConstraintTranslator constraintTranslator, Clock clock) {
        this.constraintTranslator = constraintTranslator;
        this.clock = clock;
    }

    /** Expected business and request failures raised by our own code. */
    @ExceptionHandler(PortalException.class)
    public ResponseEntity<Object> handlePortalException(PortalException exception, WebRequest request) {
        return problem(exception, exception.errorCode(), exception.getMessage(), exception.violations(), request);
    }

    /** A database constraint fired: either a known business rule (race) or a bug. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException exception, WebRequest request) {
        return constraintTranslator.translate(exception)
                .map(businessError -> handlePortalException(businessError, request))
                .orElseGet(() -> handleUnexpected(exception, request));
    }

    /** Two users changed the same resource; the second one must reload (optimistic locking). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLocking(OptimisticLockingFailureException exception, WebRequest request) {
        return problem(exception, ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was changed by someone else in the meantime. Reload it and try again.", List.of(), request);
    }

    /** The upload is not valid multipart content: the caller's mistake, not our failure. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Object> handleMalformedUpload(MultipartException exception, WebRequest request) {
        return problem(exception, ErrorCode.MALFORMED_REQUEST, "The request is not a valid multipart upload", List.of(), request);
    }

    /** Safety net: anything we did not anticipate is a bug. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        log.error("Unexpected error while handling {}", request.getDescription(false), exception);
        return problem(exception, ErrorCode.UNEXPECTED_ERROR,
                "An unexpected error occurred. Please quote the correlation id when contacting support.", List.of(), request);
    }

    /** Bean Validation on request bodies ({@code @Valid}): report every invalid field at once. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            violations.add(new Violation(fieldError.getField(), toErrorCode(fieldError.getCode()),
                    String.valueOf(fieldError.getDefaultMessage())));
        }
        exception.getBindingResult().getGlobalErrors().forEach(error -> violations.add(new Violation(
                Violation.WHOLE_RECORD, toErrorCode(error.getCode()), String.valueOf(error.getDefaultMessage()))));
        return problem(exception, ErrorCode.REQUEST_VALIDATION_FAILED, "The request contains invalid fields", violations, request);
    }

    /**
     * A body that could not be read. When our own code stopped the reading (e.g. the body is
     * too large), the caller gets that precise error instead of a generic "malformed request".
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof PortalException portalException) {
                return handlePortalException(portalException, request);
            }
        }
        return super.handleHttpMessageNotReadable(exception, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException exception,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return problem(exception, ErrorCode.IMPORT_FILE_TOO_LARGE,
                "The uploaded file exceeds the maximum allowed size", List.of(), request);
    }

    /**
     * Every response built by this class - ours and Spring MVC's built-in ones (unreadable JSON,
     * unknown route, wrong HTTP method...) - goes through here, so all of them get the same
     * shape: a {@code code}, the {@code correlationId} and a {@code timestamp}.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problemDetail) {
            if (problemDetail.getProperties() == null || !problemDetail.getProperties().containsKey("code")) {
                // A Spring MVC built-in error: not yet logged, no business code yet.
                ErrorCode code = defaultCodeFor(statusCode);
                problemDetail.setProperty("code", code.name());
                problemDetail.setType(URI.create(PROBLEM_TYPE_PREFIX + code.name()));
                log.warn("Request rejected: status={} code={} detail={}", statusCode.value(), code, problemDetail.getDetail());
            }
            problemDetail.setProperty("correlationId", MDC.get(RequestTracingFilter.CORRELATION_ID_MDC_KEY));
            problemDetail.setProperty("timestamp", clock.instant().toString());
        }
        return super.handleExceptionInternal(exception, body, headers, statusCode, request);
    }

    private ResponseEntity<Object> problem(Exception exception, ErrorCode code, String detail,
                                           List<Violation> violations, WebRequest request) {
        if (code.status().is4xxClientError()) {
            log.warn("Request rejected: status={} code={} detail={}", code.status().value(), code, detail);
        }
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problemDetail.setType(URI.create(PROBLEM_TYPE_PREFIX + code.name()));
        problemDetail.setTitle(humanTitle(code));
        problemDetail.setProperty("code", code.name());
        if (code.businessRule() != null) {
            problemDetail.setProperty("businessRule", code.businessRule());
        }
        if (!violations.isEmpty()) {
            problemDetail.setProperty("violations", violations);
        }
        return handleExceptionInternal(exception, problemDetail, new HttpHeaders(), code.status(), request);
    }

    private static ErrorCode defaultCodeFor(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return ErrorCode.UNEXPECTED_ERROR;
        }
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return ErrorCode.RESOURCE_NOT_FOUND;
        }
        if (status.value() == HttpStatus.PAYLOAD_TOO_LARGE.value()) {
            return ErrorCode.IMPORT_FILE_TOO_LARGE;
        }
        return ErrorCode.MALFORMED_REQUEST;
    }

    /** {@code OFFER_NOT_EDITABLE} -> "Offer not editable". */
    private static String humanTitle(ErrorCode code) {
        String words = code.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** Bean Validation constraint name ("NotBlank") -> stable code ("NOT_BLANK"). */
    private static String toErrorCode(String constraintName) {
        if (constraintName == null) {
            return "INVALID";
        }
        return constraintName.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }
}
