package com.jobx.dto;

import com.jobx.enums.AtsPlatform;

import java.util.List;

/**
 * One board Jobx believes matches what the user typed, with the evidence for
 * that belief attached.
 *
 * The evidence fields are not decoration. Everything except a CATALOG hit is
 * ultimately derived — read off a careers page, or guessed from a slug and then
 * checked — so the UI shows jobCount and real sampleTitles and asks the user to
 * confirm. Nothing here has been written to the database; watching the board is
 * a separate POST /watchlist the user triggers by confirming.
 *
 * @param source        how this was found: CATALOG (a board Jobx already knows),
 *                      URL (parsed straight out of an ATS link), SNIFF (read off
 *                      the careers page) or PROBE (a slug guess that turned out
 *                      to have live roles).
 * @param companyName   the board's own name for the company where the API gives
 *                      one, else the catalog name, else what the user typed.
 * @param jobCount      live openings right now. Zero only for a URL/SNIFF board
 *                      on a platform that 404s unknown tokens (Greenhouse,
 *                      Lever, Ashby), where the board provably exists. Anywhere
 *                      else an empty board is indistinguishable from a wrong
 *                      guess and is not offered.
 * @param sampleTitles  a few real titles — the thing a human actually recognises.
 * @param alreadyWatched this user already watches this board.
 */
public record ResolvedBoardResponse(
        String source,
        AtsPlatform atsPlatform,
        String boardToken,
        String companyName,
        String boardUrl,
        int jobCount,
        List<String> sampleTitles,
        boolean alreadyWatched) {
}
