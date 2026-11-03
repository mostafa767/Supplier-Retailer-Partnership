package com.equadis.portal.importing;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import com.equadis.portal.tenant.Actor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.UUID;

/**
 * HTTP layer of supplier imports (R3). No business logic here.
 *
 * <pre>
 * POST /api/imports            multipart "file" (CSV) or JSON {"offers": [...]}   -> 202 new job / 200 already sent
 * POST /api/imports/dry-run    same body                                         -> 200 what the import would do
 * GET  /api/imports/{id}                                                         -> progress, counters, problems by code
 * GET  /api/imports/{id}/errors and /errors.csv                                  -> line-by-line report
 * </pre>
 */
@RestController
@RequestMapping("/api/imports")
class ImportController {

    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final String DEFAULT_FILE_NAME = "upload.csv";
    private static final String JSON_REQUEST_NAME = "api-request.json";

    private final ImportService importService;
    private final ImportDryRunService dryRunService;

    ImportController(ImportService importService, ImportDryRunService dryRunService) {
        this.importService = importService;
        this.dryRunService = dryRunService;
    }

    /** A CSV file in the multipart field "file". */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ImportJobView> submitFile(Actor actor, @RequestPart(name = "file", required = false) MultipartFile file) {
        try (InputStream content = requireContent(file)) {
            return accepted(importService.submit(actor, ImportFormat.CSV, content, safeFileName(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read uploaded file", e);
        }
    }

    /** The same import through the API, for supplier systems: {@code {"offers": [...]}}. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ImportJobView> submitJson(Actor actor, InputStream body) {
        return accepted(importService.submit(actor, ImportFormat.JSON, body, JSON_REQUEST_NAME));
    }

    /** Checks a CSV file without importing it. */
    @PostMapping(value = "/dry-run", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportDryRunReport dryRunFile(Actor actor, @RequestPart(name = "file", required = false) MultipartFile file) {
        try (InputStream content = requireContent(file)) {
            return dryRunService.check(actor, ImportFormat.CSV, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read uploaded file", e);
        }
    }

    /** Checks a JSON request without importing it. */
    @PostMapping(value = "/dry-run", consumes = MediaType.APPLICATION_JSON_VALUE)
    ImportDryRunReport dryRunJson(Actor actor, InputStream body) {
        return dryRunService.check(actor, ImportFormat.JSON, body);
    }

    /** Progress, counters and problems grouped by code. */
    @GetMapping("/{importId}")
    ImportJobView get(Actor actor, @PathVariable UUID importId) {
        return importService.get(actor, importId);
    }

    /** The line-by-line error report, one page at a time. */
    @GetMapping("/{importId}/errors")
    ImportErrorPage errors(Actor actor, @PathVariable UUID importId,
                           @RequestParam(defaultValue = "0") long afterId,
                           @RequestParam(defaultValue = "100") int size) {
        return importService.errorPage(actor, importId, afterId, size);
    }

    /** The whole error report as a CSV download. */
    @GetMapping(value = "/{importId}/errors.csv", produces = "text/csv")
    void errorsAsCsv(Actor actor, @PathVariable UUID importId, HttpServletResponse response) throws IOException {
        importService.get(actor, importId); // ownership check BEFORE anything is written to the response
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-" + importId + "-errors.csv\"");
        importService.writeErrorReport(actor, importId, response.getWriter());
    }

    /** 202 and a Location for a new job; 200 when the same offers had already been sent. */
    private static ResponseEntity<ImportJobView> accepted(ImportJobView job) {
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/imports/{id}").buildAndExpand(job.id()).toUri();
        HttpStatus status = job.alreadySubmitted() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).location(location).body(job);
    }

    private static InputStream requireContent(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new PortalException(ErrorCode.IMPORT_FILE_MISSING, "Send the CSV file in the multipart field 'file'");
        }
        return file.getInputStream();
    }

    /**
     * The name the supplier gave the file, only displayed back. Control characters are removed
     * because they could fake log lines, and NUL cannot be stored at all.
     */
    private static String safeFileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return DEFAULT_FILE_NAME;
        }
        String withoutPath = name.replace('\\', '/');
        withoutPath = withoutPath.substring(withoutPath.lastIndexOf('/') + 1);
        String printable = withoutPath.replaceAll("\\p{Cntrl}", "");
        if (printable.isBlank()) {
            return DEFAULT_FILE_NAME;
        }
        return printable.length() > MAX_FILE_NAME_LENGTH ? printable.substring(0, MAX_FILE_NAME_LENGTH) : printable;
    }
}
