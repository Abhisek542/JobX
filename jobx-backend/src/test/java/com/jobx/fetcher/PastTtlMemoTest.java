package com.jobx.fetcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PastTtlMemoTest {

    @Test
    void remembersPerBoard() {
        PastTtlMemo memo = new PastTtlMemo();
        memo.remember("acme", "A1");

        assertTrue(memo.contains("acme", "A1"));
        // The same id on another board is a different posting.
        assertFalse(memo.contains("other", "A1"));
        assertFalse(memo.contains("acme", "A2"));
    }

    @Test
    void clearsInsteadOfGrowingPastItsBound() {
        PastTtlMemo memo = new PastTtlMemo();
        for (int i = 0; i < PastTtlMemo.MAX_ENTRIES; i++) {
            memo.remember("acme", "id" + i);
        }
        assertEquals(PastTtlMemo.MAX_ENTRIES, memo.size());

        memo.remember("acme", "one-more");

        assertEquals(1, memo.size());
        assertTrue(memo.contains("acme", "one-more"));
    }
}
