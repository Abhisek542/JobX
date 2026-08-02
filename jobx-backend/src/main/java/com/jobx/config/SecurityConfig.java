package com.jobx.config;

import com.jobx.security.JwtAuthenticationFilter;
import com.jobx.security.RateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final Environment environment;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // /error must stay open too: a ResponseStatusException (e.g. 409 on
        // duplicate register) triggers an internal forward to /error, and
        // without this, AuthorizationFilter denies that forward for anonymous
        // callers and the entry point below overwrites the real status/body
        // with a generic 401. (GlobalExceptionHandler now catches most of these
        // before the forward happens, but keep the net.)
        List<String> open = new ArrayList<>(List.of("/auth/**", "/error"));
        // /dev/** is dev-profile tooling — outside dev the controller bean
        // doesn't exist and the path falls under authenticated like anything else
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            open.add("/dev/**");
        }

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(open.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            // Same shape as ApiError — keep in sync with GlobalExceptionHandler
                            response.getWriter().write(
                                    "{\"status\":401,\"code\":\"unauthorized\",\"detail\":\"authentication required\"}");
                        })
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Rate limiter runs before JWT parsing — auth endpoints are anonymous
                .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class);
        return http.build();
    }
}
