package com.jobx.util;

import java.util.Locale;

/**
 * Canonical form for account emails: trimmed and lower-cased. Applied in the
 * auth request DTOs so register and login can never disagree on casing, and
 * backed by the {@code LOWER(email)} unique index from V7.
 */
public final class Emails {

    private Emails() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        // Locale.ROOT: the default locale would turn "I" into a dotless ı on a Turkish JVM.
        return raw.strip().toLowerCase(Locale.ROOT);
    }
}
