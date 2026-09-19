package com.jobx.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GlobalExceptionHandlerTest proves the mapping; this proves the wiring — that a
 * framework rejection genuinely reaches the advice rather than the container's
 * error page. That distinction is the substance of BUG_REPORT #7: the mapping was
 * never wrong, the exceptions simply never arrived anywhere that could map them.
 *
 * The 405 case is the sharp one. RequestMappingHandlerMapping throws it out of
 * getHandler, so processHandlerException runs with handler == null — this test is
 * what says a @ControllerAdvice is still consulted in that state, and that the
 * Allow header survives the trip.
 *
 * What this setup deliberately cannot prove, checked live with curl instead:
 * - StandaloneMockMvcBuilder registers no ResourceHttpRequestHandler, so an
 *   unmapped path here throws NoHandlerFoundException, never the production
 *   NoResourceFoundException. The real exception object is covered by a unit test.
 * - Nothing about Spring Security: the 401-before-404 an anonymous caller gets, the
 *   entry point, or the /error forward.
 *
 * The probe controller is a fixture rather than a real one so that a required
 * @RequestParam exists at all — CompanyController's is the only one in the app and
 * it is required = false.
 */
class GlobalExceptionHandlerWiringTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void wrongMethodReachesTheAdviceAndKeepsTheAllowHeader() throws Exception {
        mvc.perform(get("/probe"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("method_not_allowed"));
    }

    @Test
    void missingRequiredParameterReachesTheAdvice() throws Exception {
        mvc.perform(get("/probe/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("missing_parameter"))
                .andExpect(jsonPath("$.detail").value("parameter 'q' is required"));
    }

    @Test
    void unsupportedContentTypeReachesTheAdvice() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().exists(HttpHeaders.ACCEPT))
                .andExpect(jsonPath("$.code").value("unsupported_media_type"));
    }

    @Test
    void aSuppliedParameterStillReachesTheController() throws Exception {
        mvc.perform(get("/probe/search").param("q", "razorpay"))
                .andExpect(status().isOk());
    }

    @RestController
    static class ProbeController {

        /**
         * The body is a record, not a String, on purpose: StringHttpMessageConverter
         * reads text/plain quite happily, so a String body would answer 200 and the
         * 415 path would never be exercised. Only Jackson can read a record, and it
         * speaks application/json alone.
         */
        @PostMapping("/probe")
        ProbeBody create(@RequestBody ProbeBody body) {
            return body;
        }

        @GetMapping("/probe/search")
        String search(@RequestParam("q") String q) {
            return q;
        }
    }

    record ProbeBody(String name) {
    }
}
