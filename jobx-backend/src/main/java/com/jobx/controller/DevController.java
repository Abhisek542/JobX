package com.jobx.controller;

import com.jobx.scheduler.FetchScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only tooling — the bean simply doesn't exist outside the 'dev' profile,
 * and SecurityConfig only permits /dev/** anonymously in dev too.
 */
@RestController
@RequestMapping("/dev")
@RequiredArgsConstructor
@Profile("dev")
public class DevController {

    private final FetchScheduler fetchScheduler;

    @PostMapping("/fetch")
    public String triggerFetch() {
        fetchScheduler.fetchAllCompanies();
        return "fetch triggered";
    }


}