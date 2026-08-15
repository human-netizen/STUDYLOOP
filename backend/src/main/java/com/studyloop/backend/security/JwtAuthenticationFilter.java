package com.studyloop.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// Not a @Component: SecurityConfig instantiates it directly, so it lives only inside
// the security chain (a Filter bean would also be auto-registered with the servlet
// container and run outside it).
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    // A request that goes async (the SSE chat stream) comes back through the whole filter chain a
    // second time as an ASYNC dispatch when the emitter completes, and a container-level error is
    // dispatched to /error the same way. OncePerRequestFilter skips both by default, so without
    // these overrides the second pass runs with an empty SecurityContext — this filter puts the
    // Authentication straight on the holder rather than into a SecurityContextRepository, so
    // nothing restores it — and AuthorizationFilter (which does filter every dispatch type) denies
    // the request. By then the response is committed, so Tomcat can neither report the error nor
    // finish the chunked body, and the client sees a truncated stream. The Bearer header is still
    // on the request both times, so re-authenticating is just a second signature check.
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                // Only access tokens authenticate requests — a refresh token is not a badge.
                if (JwtService.TYPE_ACCESS.equals(claims.get(JwtService.CLAIM_TYPE, String.class))) {
                    var authentication = new UsernamePasswordAuthenticationToken(
                            claims.getSubject(),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + claims.get("role", String.class))));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException | IllegalArgumentException ex) {
                // Invalid or expired token → request stays anonymous; authorization answers 401.
            }
        }
        filterChain.doFilter(request, response);
    }
}
