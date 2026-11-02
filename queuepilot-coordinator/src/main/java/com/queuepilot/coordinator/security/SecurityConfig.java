package com.queuepilot.coordinator.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queuepilot.coordinator.web.ApiError;
import com.queuepilot.coordinator.worker.WorkerRegistryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Security model (ADR-008):
 * <ul>
 *   <li>{@code /api/admin/**}: HTTP Basic, role ADMIN</li>
 *   <li>{@code /api/worker/register}: open, but requires the enrollment secret in the body</li>
 *   <li>{@code /api/worker/**}: per-worker bearer token, role WORKER</li>
 *   <li>everything else (client API, dashboard): open</li>
 * </ul>
 * Stateless: no sessions or cookies, therefore CSRF protection is not needed.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, WorkerRegistryService workerRegistry,
                                                   ObjectMapper objectMapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, e) ->
                        writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Authentication required")))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Authentication required"))
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, objectMapper, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Not allowed")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/worker/register").permitAll()
                        .requestMatchers("/api/worker/**").hasRole("WORKER")
                        .anyRequest().permitAll())
                .addFilterBefore(new WorkerTokenFilter(workerRegistry), BasicAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService adminUsers(SecretsProvider secrets, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User.withUsername(secrets.adminUser())
                .password(encoder.encode(secrets.adminPassword()))
                .roles("ADMIN")
                .build());
    }

    /** Writes a JSON error without a WWW-Authenticate header, so browsers do not pop up a login dialog. */
    private static void writeError(HttpServletResponse response, ObjectMapper mapper, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiError(code, message, List.of(), Instant.now()));
    }
}
