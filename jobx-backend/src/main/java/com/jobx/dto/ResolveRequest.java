package com.jobx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The one thing the add-company flow asks a user for: a company name, a website,
 * or a link to a careers page. Which of the three it is, is the resolver's
 * problem, not the user's.
 */
public record ResolveRequest(
        @NotBlank(message = "enter a company name, website or careers link")
        @Size(max = 2000, message = "that's too long to be a company or a link")
        String query) {
}
