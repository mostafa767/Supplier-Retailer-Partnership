-- =====================================================================================
-- V9 - Offers can also be sent as JSON through the API (R3: "as a file (CSV) or through an
-- API, with up to 100,000 offers at a time").
--
-- A JSON request becomes the same kind of import job as a CSV file, so it gets the same
-- chunked processing, line-by-line report and protection against duplicates. The format is
-- kept to report problems the way the supplier sent them.
-- =====================================================================================

ALTER TABLE import_job
    ADD COLUMN source_format VARCHAR(8) NOT NULL DEFAULT 'CSV' CHECK (source_format IN ('CSV', 'JSON'));

COMMENT ON COLUMN import_error.line_number IS
    'CSV import: line of the file (header = 1). JSON import: index of the offer in the "offers" array (first = 0).';
