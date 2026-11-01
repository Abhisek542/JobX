package com.jobx.dto;

import com.jobx.enums.ResolveMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What the add-company flow asks a user for: a company name, or a link to the
 * company's careers page, and which of the two it is.
 *
 * The mode is explicit rather than guessed so that each input takes one
 * predictable path. Validation messages are static, so the URL-mode "that isn't
 * a link" message comes from {@code CompanyResolver}, not from here.
 */
public record ResolveRequest(
        @NotNull(message = "choose whether to look up a company name or a careers page link")
        ResolveMode mode,

        @NotBlank(message = "enter a company name or a careers page link")
        @Size(max = 2000, message = "that's too long to be a company or a link")
        String query) {
}
