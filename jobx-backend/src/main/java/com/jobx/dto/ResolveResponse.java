package com.jobx.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jobx.enums.AtsPlatform;

import java.util.List;

/**
 * What resolution found. An empty candidate list is a normal answer, not an
 * error — plenty of companies are on a portal with no public API, and saying so
 * is the honest outcome (CLAUDE.md: "mark portal unsupported rather than faking
 * support").
 *
 * @param platformHint set when the careers page named an ATS but never named the
 *                     board — a client-rendered board, or a host that only
 *                     appears in a CSP header. Lets the UI say "Atlan looks like
 *                     it uses Ashby, but we couldn't read the board id".
 */
public record ResolveResponse(
        List<ResolvedBoardResponse> candidates,
        AtsPlatform platformHint) {

    /** Convenience for callers — not part of the JSON contract. */
    @JsonIgnore
    public boolean isEmpty() {
        return candidates == null || candidates.isEmpty();
    }
}
