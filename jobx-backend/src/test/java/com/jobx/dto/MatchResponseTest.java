package com.jobx.dto;

import com.jobx.entity.Company;
import com.jobx.entity.Job;
import com.jobx.entity.Match;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchResponseTest {

    private Match match() {
        Company company = new Company();
        company.setId(UUID.randomUUID());
        company.setDisplayName("Aspora");
        Match match = new Match();
        match.setId(UUID.randomUUID());
        match.setCompany(company);
        match.setJobTitle("Backend Engineer");
        return match;
    }

    @Test
    void carriesTheJobsDisplayLabels() {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setDepartment("Engineering");
        job.setTeam("Payments");
        job.setEmploymentType("Full-time");
        Match match = match();
        match.setJob(job);

        MatchResponse response = MatchResponse.from(match);

        assertEquals("Engineering", response.department());
        assertEquals("Payments", response.team());
        assertEquals("Full-time", response.employmentType());
    }

    /** After the TTL sweep the job is gone; the card renders without the labels. */
    @Test
    void anExpiredMatchHasNoLabels() {
        MatchResponse response = MatchResponse.from(match());

        assertNull(response.jobId());
        assertNull(response.department());
        assertNull(response.team());
        assertNull(response.employmentType());
    }
}
