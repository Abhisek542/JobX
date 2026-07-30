package com.jobx.security;

import com.jobx.entity.User;
import com.jobx.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Stateless bearer-token auth: on a valid token, loads the User and sets it
 * directly as the Authentication principal, so controllers can bind it with
 * @AuthenticationPrincipal User user instead of a UserDetails wrapper.
 *
 * A missing/invalid/expired token just leaves the SecurityContext empty —
 * authorizeHttpRequests + the entry point turn that into a 401 for protected
 * routes, and /auth/** and /dev/** stay reachable either way.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                UUID userId = UUID.fromString(jwtService.parseAndValidate(header.substring(7)).getSubject());
                User user = userRepository.findById(userId).orElse(null);
                if (user != null) {
                    List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
                    var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // invalid/expired/malformed token or bad UUID — treat as unauthenticated, don't 500
            }
        }
        filterChain.doFilter(request, response);
    }
}
