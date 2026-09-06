package com.jobx.fetcher;

import java.util.List;

/**
 * What one cheap list call to an ATS board tells us about it, before any user
 * commits to watching it.
 *
 * This is the evidence behind the add-company confirmation card. The whole
 * point of the resolve flow is that Jobx may *propose* a board token it worked
 * out from a careers URL or a slug guess, but a human confirms it against real
 * roles — so a preview that cannot show live titles is not worth showing.
 *
 * @param displayName the board's own name for the company, where the API gives
 *                    one (Workable's {@code name}, SmartRecruiters'
 *                    {@code company.name}). Null on Greenhouse, Lever and Ashby,
 *                    whose list endpoints carry no company name — callers fall
 *                    back to the catalog name, the domain label, or what the
 *                    user typed.
 * @param jobCount    live, listed openings after the platform's own filtering
 *                    (Greenhouse prospect posts and Ashby unlisted posts are
 *                    already excluded, Workable shortcodes already deduped).
 * @param sampleTitles up to a handful of real titles, in board order.
 */
public record BoardPreview(String displayName, int jobCount, List<String> sampleTitles) {

    /** How many titles a preview carries — enough to recognise a company, not a feed. */
    public static final int SAMPLE_SIZE = 3;

    public BoardPreview {
        sampleTitles = sampleTitles == null ? List.of() : List.copyOf(sampleTitles);
    }

    public static BoardPreview empty() {
        return new BoardPreview(null, 0, List.of());
    }
}
