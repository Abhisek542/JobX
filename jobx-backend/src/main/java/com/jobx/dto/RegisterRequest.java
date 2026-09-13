package com.jobx.dto;

import com.jobx.util.Emails;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password
) {
    // Runs at deserialization, before @Valid — validation sees the canonical email.
    public RegisterRequest {
        email = Emails.normalize(email);
    }
}
