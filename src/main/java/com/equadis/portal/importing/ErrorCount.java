package com.equadis.portal.importing;

/**
 * How often one kind of problem occurs in an import, e.g. 38 x DATE_INVALID. The first thing a
 * supplier needs: one fix in its export often solves hundreds of lines.
 */
record ErrorCount(String code, long count) {
}
