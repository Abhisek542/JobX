package com.jobx.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitFilterTest {

    private static final int MAX_ATTEMPTS = 3;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        // Wide window so the test can't straddle a boundary
        filter = new RateLimitFilter(MAX_ATTEMPTS, 3600);
    }

    private MockHttpServletResponse request(String ip, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void allowsUpToMaxThenRejectsWith429() throws Exception {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertEquals(200, request("1.2.3.4", "/auth/login").getStatus());
        }

        MockHttpServletResponse rejected = request("1.2.3.4", "/auth/login");
        assertEquals(429, rejected.getStatus());
        assertNotNull(rejected.getHeader("Retry-After"));
        // ApiError-shaped body
        assertTrue(rejected.getContentAsString().contains("\"code\":\"rate_limited\""));
    }

    @Test
    void budgetIsPerIp() throws Exception {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            request("1.2.3.4", "/auth/login");
        }
        assertEquals(429, request("1.2.3.4", "/auth/login").getStatus());

        // A different caller is unaffected
        assertEquals(200, request("5.6.7.8", "/auth/login").getStatus());
    }

    @Test
    void budgetIsPerEndpoint() throws Exception {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            request("1.2.3.4", "/auth/login");
        }
        assertEquals(429, request("1.2.3.4", "/auth/login").getStatus());

        // Register has its own budget
        assertEquals(200, request("1.2.3.4", "/auth/register").getStatus());
    }

    @Test
    void nonAuthPathsAreNeverLimited() throws Exception {
        for (int i = 0; i < MAX_ATTEMPTS * 3; i++) {
            assertEquals(200, request("1.2.3.4", "/matches").getStatus());
        }
    }
}
