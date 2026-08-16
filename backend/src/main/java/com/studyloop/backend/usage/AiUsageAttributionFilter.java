package com.studyloop.backend.usage;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

// Names the person behind every provider call made while serving a request, once, at the edge.
//
// The alternative was to pass an actor into each of the dozen services that can reach a provider,
// and then into ChatClient and EmbeddingClient, which is exactly the parameter AiUsageContext
// exists to avoid. Here the actor is set in one place from the Authentication the JWT filter just
// established, and unset in a finally — pooled request threads are reused, and a leaked actor
// would bill the next request to the previous user.
//
// Not a @Component: like JwtAuthenticationFilter, a Filter bean would also be auto-registered
// with the servlet container and run outside the security chain, where the SecurityContext is not
// yet populated. SecurityConfig places it immediately after the filter that authenticates.
//
// Two paths escape it, both because the provider call happens on a thread this request never
// touches: the SSE stream (chat executor) and ingestion (ingestion executor). Both know their
// actor and open their own scope — see ChatStreamService and DocumentIngestionListener.
public class AiUsageAttributionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UUID actorId = currentUserId();
        if (actorId == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try (var ignored = AiUsageContext.actor(actorId)) {
            filterChain.doFilter(request, response);
        }
    }

    // The principal is the user id as a string (JwtAuthenticationFilter puts the token's subject
    // there). Anything else — anonymous, or a principal shape we don't recognise — attributes to
    // nobody rather than guessing.
    private static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
