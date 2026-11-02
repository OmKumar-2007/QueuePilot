package com.queuepilot.coordinator.security;

import com.queuepilot.coordinator.worker.WorkerRegistryService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates worker API calls with the per-worker bearer token issued at registration.
 * Requests without a valid token stay anonymous and are rejected by the security rules.
 */
public class WorkerTokenFilter extends OncePerRequestFilter {

    private final WorkerRegistryService workerRegistry;

    public WorkerTokenFilter(WorkerRegistryService workerRegistry) {
        this.workerRegistry = workerRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/worker/") || path.equals("/api/worker/register");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            workerRegistry.authenticate(header.substring("Bearer ".length()).trim()).ifPresent(workerId -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        new WorkerPrincipal(workerId), null, List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }
}
