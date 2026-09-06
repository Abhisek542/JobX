package com.jobx.resolve;

import com.jobx.enums.AtsPlatform;

/**
 * A board identified by platform and token — the pair the rest of Jobx is keyed
 * on ({@code companies} is UNIQUE (ats_platform, board_token)), before anything
 * has confirmed the board is real.
 *
 * A BoardRef is a claim, not a fact. It comes out of a URL the user pasted or a
 * slug the resolver guessed; only a successful
 * {@link com.jobx.fetcher.AtsFetcher#previewBoard} turns it into something worth
 * showing a user.
 */
public record BoardRef(AtsPlatform platform, String token) {
}
