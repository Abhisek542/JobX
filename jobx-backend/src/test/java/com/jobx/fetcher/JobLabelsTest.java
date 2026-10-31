package com.jobx.fetcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class JobLabelsTest {

    @Test
    void blankIsNull() {
        assertNull(JobLabels.clean(null));
        assertNull(JobLabels.clean(""));
        assertNull(JobLabels.clean("   "));
        assertNull(JobLabels.employmentType(" "));
    }

    @Test
    void trimsAndCollapsesWhitespace() {
        assertEquals("Data Platform", JobLabels.clean("  Data \n Platform "));
    }

    @Test
    void capsTheLength() {
        assertEquals(JobLabels.MAX_LENGTH, JobLabels.clean("x".repeat(500)).length());
    }

    /** One spelling per common type, whatever the platform wrote. */
    @ParameterizedTest
    @CsvSource({
            "FullTime, Full-time",      // Ashby
            "Full Time, Full-time",     // Lever
            "Full time, Full-time",     // Workday
            "Full-Time, Full-time",     // BambooHR
            "full, Full-time",          // Workable v2
            "PartTime, Part-time",
            "Intern, Internship",
            "Contractor, Contract",
            "Temporary, Temporary",
    })
    void normalisesCommonEmploymentTypes(String raw, String expected) {
        assertEquals(expected, JobLabels.employmentType(raw));
    }

    @Test
    void keepsAnUnknownEmploymentTypeAsWritten() {
        // Sprinto's Lever board puts "Remote" in commitment. Not ours to fix.
        assertEquals("Remote", JobLabels.employmentType(" Remote "));
    }
}
