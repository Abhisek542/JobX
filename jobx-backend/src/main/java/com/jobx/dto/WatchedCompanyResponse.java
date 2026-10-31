package com.jobx.dto;

import com.jobx.entity.Company;
import com.jobx.entity.WatchedCompany;
import com.jobx.enums.AtsPlatform;
import com.jobx.resolve.AtsUrlParser;

import java.time.Instant;
import java.util.UUID;

/**
 * lastFetchStatus drives the watchlist's health line: null = "not checked yet",
 * SUCCESS = "last checked {lastFetchedAt}", FAILED = the "Refresh issue"
 * warning state. The stored last_fetch_error is intentionally NOT exposed —
 * V1_IMPROVEMENTS.md keeps the raw cause server-side.
 *
 * JSON shape is unchanged by V4 — the Angular app needs no changes — but the
 * fields now resolve through the shared Company: companyName is the canonical
 * displayName, and the fetch-health trio is board-wide, not per-watch-row.
 *
 * movedTo (PR D) is the board BoardRedetector thinks this company moved to, or
 * null. A proposal: the watchlist shows a Switch banner, and
 * POST /watchlist/{id}/move is what acts on it.
 */
public record WatchedCompanyResponse(
        /** The watch row — what PATCH/DELETE /watchlist/{id} addresses. */
        UUID id,
        /**
         * The shared board behind that watch row, and the only thing that
         * matches this watch to a MatchResponse: the two ids are different, so
         * without this the client could only join them by display name.
         */
        UUID companyId,
        String companyName,
        AtsPlatform atsPlatform,
        String boardToken,
        WatchedCompany.CompanyStatus status,
        Instant lastFetchedAt,
        Company.FetchStatus lastFetchStatus,
        Instant createdAt,
        MovedTo movedTo
) {
    /** A proposed new board for the company. */
    public record MovedTo(AtsPlatform atsPlatform, String boardToken, String boardUrl,
                          Company.MoveReason reason) {
        static MovedTo of(Company company) {
            if (!company.hasMoveProposal()) {
                return null;
            }
            return new MovedTo(company.getMovedToPlatform(), company.getMovedToToken(),
                    AtsUrlParser.boardUrl(company.getMovedToPlatform(), company.getMovedToToken()),
                    company.getMovedToReason());
        }
    }

    public static WatchedCompanyResponse from(WatchedCompany watch) {
        Company company = watch.getCompany();
        return new WatchedCompanyResponse(
                watch.getId(),
                company.getId(),
                company.getDisplayName(),
                company.getAtsPlatform(),
                company.getBoardToken(),
                watch.getStatus(),
                company.getLastFetchedAt(),
                company.getLastFetchStatus(),
                watch.getCreatedAt(),
                MovedTo.of(company)
        );
    }
}
