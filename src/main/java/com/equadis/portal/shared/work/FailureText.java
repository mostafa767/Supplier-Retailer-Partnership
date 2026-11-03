package com.equadis.portal.shared.work;

/** Turns a failure into the short text stored with the work that failed. */
public final class FailureText {

    private static final int MAX_LENGTH = 1000;

    private FailureText() {
    }

    /** @return the failure's description, cut to fit the {@code last_error} columns */
    public static String of(Throwable failure) {
        String text = failure.toString().replace("\u0000", "");
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH) + "...";
    }
}
