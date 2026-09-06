package com.jobx.controller;

import com.jobx.dto.CompanySearchResponse;
import com.jobx.entity.User;
import com.jobx.resolve.CompanyResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The add-company typeahead.
 *
 * Since V4 the companies table is a registry of boards that have been proven
 * real — every row is one somebody successfully added and Jobx has fetched — so
 * searching it is the cheapest and most certain way to add a company: no ATS
 * call, no URL to find, no guessing. It also compounds, because every user who
 * adds a board makes it a one-click add for the next.
 *
 * Deliberately exposes boards across all users. They are public job boards, and
 * the response carries nothing about who watches them beyond whether the caller
 * does.
 */
@RestController
@RequestMapping("/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyResolver companyResolver;

    /**
     * Boards whose name or token contains {@code q}. Runs on keystrokes, so it
     * never touches the network; a query shorter than two characters returns
     * nothing rather than the whole table.
     */
    @GetMapping("/search")
    public List<CompanySearchResponse> search(@AuthenticationPrincipal User user,
                                              @RequestParam(name = "q", required = false) String q) {
        return companyResolver.search(user, q);
    }
}
