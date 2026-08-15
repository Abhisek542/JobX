package com.jobx.fetcher;

/**
 * A board could not be fetched or its response could not be understood —
 * transport error, non-2xx status, unparseable body, or a payload missing the
 * job collection entirely.
 *
 * Deliberately distinct from "the board returned zero jobs", which is a normal,
 * successful outcome. Fetchers used to collapse both into an empty list, which
 * made a dead board indistinguishable from a quiet one; FetchScheduler now
 * catches this to record FAILED health and to keep the rest of the cycle going.
 */
public class AtsFetchException extends RuntimeException {

    public AtsFetchException(String message, Throwable cause) {
        super(message, cause);
    }

    public AtsFetchException(String message) {
        super(message);
    }
}
