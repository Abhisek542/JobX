package com.jobx.dto;

import com.jobx.enums.AtsPlatform;

import java.util.UUID;

/**
 * A board Jobx already knows about, offered as the user types.
 *
 * Since V4 the companies table is a registry of boards that have been proven
 * real — every row is one somebody successfully added — so a catalog hit needs
 * no network call and no guessing at all. It is the cheapest and most certain
 * way to add a company, and it gets better as more users join.
 */
public record CompanySearchResponse(
        UUID companyId,
        String companyName,
        AtsPlatform atsPlatform,
        String boardToken,
        boolean alreadyWatched) {
}
