package com.jobx.dto;

import com.jobx.util.Emails;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
    // Runs at deserialization, before @Valid — validation sees the canonical email.
    public LoginRequest {
        email = Emails.normalize(email);
    }
}
