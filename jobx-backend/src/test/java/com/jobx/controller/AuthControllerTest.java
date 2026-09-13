package com.jobx.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobx.dto.AuthResponse;
import com.jobx.dto.LoginRequest;
import com.jobx.dto.RegisterRequest;
import com.jobx.entity.User;
import com.jobx.repository.UserRepository;
import com.jobx.security.JwtService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BUG_REPORT #3: an email registered as "Abhi@Example.com" must log in as
 * "abhi@example.com", and a case variant must not become a second account.
 */
class AuthControllerTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        controller = new AuthController(userRepository, passwordEncoder, jwtService);

        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(jwtService.generateToken(any())).thenReturn("token");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
    }

    @Test
    void registerStoresTheCanonicalEmail() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        AuthResponse response = controller.register(new RegisterRequest("  Abhi@Example.COM ", "password1"));

        verify(userRepository).findByEmail("abhi@example.com");
        verify(userRepository).save(argThat(u -> u.getEmail().equals("abhi@example.com")));
        assertEquals("abhi@example.com", response.email());
    }

    @Test
    void registerRejectsACaseVariantOfAnExistingAccount() {
        when(userRepository.findByEmail("abhi@example.com")).thenReturn(Optional.of(storedUser()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                controller.register(new RegisterRequest("ABHI@example.com", "password1")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    void loginMatchesRegardlessOfCasing() {
        User stored = storedUser();
        when(userRepository.findByEmail("abhi@example.com")).thenReturn(Optional.of(stored));
        when(passwordEncoder.matches("password1", "hash")).thenReturn(true);

        AuthResponse response = controller.login(new LoginRequest("ABHI@example.com", "password1"));

        assertEquals("token", response.token());
        assertEquals(stored.getId(), response.userId());
    }

    @Test
    void jsonWithPaddedMixedCaseEmailPassesValidation() throws Exception {
        // Guards the ordering assumption: the record normalizes during Jackson
        // construction, so @Email never sees the surrounding whitespace.
        RegisterRequest request = new ObjectMapper().readValue(
                "{\"email\":\" A.B@Example.com \",\"password\":\"password1\"}", RegisterRequest.class);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertTrue(validator.validate(request).isEmpty());
        }
        assertEquals("a.b@example.com", request.email());
    }

    private User storedUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("abhi@example.com");
        user.setPasswordHash("hash");
        return user;
    }
}
