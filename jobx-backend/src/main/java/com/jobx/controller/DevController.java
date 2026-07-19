package com.jobx.controller;

import com.jobx.scheduler.FetchScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dev")
@RequiredArgsConstructor
public class DevController {

    private final FetchScheduler fetchScheduler;

    @PostMapping("/fetch")
    public String triggerFetch() {
        fetchScheduler.fetchAllCompanies();
        return "fetch triggered";
    }

}