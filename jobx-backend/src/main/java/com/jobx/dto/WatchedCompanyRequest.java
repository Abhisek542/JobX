package com.jobx.dto;

import com.jobx.enums.AtsPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * POST /watchlist body — adds a new company to watch.
 * status is always ACTIVE on creation; use PATCH /watchlist/{id} to pause it.
 *
 * careersUrl is optional: the careers page the board was found on, which the
 * modal sends when the user added the company in URL mode and the chosen
 * candidate came from that link (source URL or SNIFF). It is stored on the
 * company only if the company has none yet, and BoardRedetector sniffs it again
 * if the board later starts failing (PR D). Only ever fetched through
 * SafeUrlFetcher, so a bad value costs at most one guarded GET.
 */
public record WatchedCompanyRequest(
        @NotBlank(message = "companyName is required") String companyName,
        @NotNull(message = "atsPlatform is required") AtsPlatform atsPlatform,
        @NotBlank(message = "boardToken is required — never guess it, read it from the live careers URL")
        String boardToken,
        @Size(max = 2000, message = "careersUrl is too long")
        String careersUrl
) {
    /** Manual adds and older clients send no careers page. */
    public WatchedCompanyRequest(String companyName, AtsPlatform atsPlatform, String boardToken) {
        this(companyName, atsPlatform, boardToken, null);
    }
}
