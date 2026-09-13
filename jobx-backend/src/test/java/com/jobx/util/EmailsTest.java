package com.jobx.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class EmailsTest {

    @Test
    void nullStaysNull() {
        assertNull(Emails.normalize(null));
    }

    @Test
    void trimsAndLowerCases() {
        assertEquals("abhi@example.com", Emails.normalize("  Abhi@Example.COM \t"));
    }

    @Test
    void blankBecomesEmptySoNotBlankStillRejectsIt() {
        assertEquals("", Emails.normalize("   "));
    }

    @Test
    void ignoresTheJvmDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertEquals("info@example.com", Emails.normalize("INFO@EXAMPLE.COM"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
