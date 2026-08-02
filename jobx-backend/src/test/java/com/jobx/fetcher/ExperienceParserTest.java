package com.jobx.fetcher;

import com.jobx.entity.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExperienceParserTest {

    private Job parse(String text) {
        Job job = new Job();
        ExperienceParser.parse(text, job);
        return job;
    }

    @Test
    void parsesRange() {
        Job job = parse("We need 3-5 years of experience in campaign management");
        assertEquals(3, job.getExpMin());
        assertEquals(5, job.getExpMax());
    }

    @Test
    void parsesEnDashRangeAndYrsAbbreviation() {
        Job job = parse("Looking for 2–4 yrs in backend work");
        assertEquals(2, job.getExpMin());
        assertEquals(4, job.getExpMax());
    }

    @Test
    void parsesPlusAsMinOnly() {
        Job job = parse("Requires 5+ years building distributed systems");
        assertEquals(5, job.getExpMin());
        assertNull(job.getExpMax());
    }

    @Test
    void parsesMinimumKeyword() {
        Job job = parse("Minimum 2 years of professional experience");
        assertEquals(2, job.getExpMin());
        assertNull(job.getExpMax());
    }

    @Test
    void rangeWinsOverPlus() {
        // "X-Y years" is most specific and is tried first
        Job job = parse("1-3 years preferred; 5+ years is a bonus");
        assertEquals(1, job.getExpMin());
        assertEquals(3, job.getExpMax());
    }

    @Test
    void noMatchLeavesBothNull() {
        Job job = parse("A great role for curious engineers");
        assertNull(job.getExpMin());
        assertNull(job.getExpMax());
    }
}
