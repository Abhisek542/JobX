package com.jobx.dto;

import com.jobx.enums.AtsPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Recorded when resolution dead-ends, so that "which ATS should Jobx support
 * next" is answered by what users actually asked for rather than by guesswork.
 */
public record UnsupportedBoardReportRequest(
        @NotBlank(message = "query is required")
        @Size(max = 2000, message = "that's too long to be a company or a link")
        String query,

        /** Whatever the sniffer managed to work out, if anything. Usually null. */
        AtsPlatform platformHint) {
}
