package com.studyloop.backend.auth;

import com.studyloop.backend.auth.dto.LoginRequest;
import com.studyloop.backend.auth.dto.RefreshRequest;
import com.studyloop.backend.auth.dto.RegisterRequest;
import com.studyloop.backend.auth.dto.TokenResponse;
import com.studyloop.backend.auth.dto.UserResponse;
import com.studyloop.backend.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName());
        // Flush now so @CreationTimestamp is filled before we build the response.
        userRepository.saveAndFlush(user);
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT);
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (AuthenticationException ex) {
            // Unknown email and wrong password both land here — no user enumeration.
            throw new InvalidCredentialsException();
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidCredentialsException::new);
        return issueTokens(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshRequest request) {
        Claims claims;
        try {
            claims = jwtService.parse(request.refreshToken());
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException();
        }
        if (!JwtService.TYPE_REFRESH.equals(claims.get(JwtService.CLAIM_TYPE, String.class))) {
            throw new InvalidTokenException();
        }
        User user = userRepository.findById(UUID.fromString(claims.getSubject()))
                .orElseThrow(InvalidTokenException::new);
        return issueTokens(user);
    }

    private TokenResponse issueTokens(User user) {
        return TokenResponse.of(
                jwtService.generateAccessToken(user),
                jwtService.generateRefreshToken(user),
                user);
    }
}
