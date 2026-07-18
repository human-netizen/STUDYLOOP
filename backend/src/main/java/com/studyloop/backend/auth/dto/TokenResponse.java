package com.studyloop.backend.auth.dto;

import com.studyloop.backend.auth.User;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        UserResponse user
) {

    public static TokenResponse of(String accessToken, String refreshToken, User user) {
        return new TokenResponse(accessToken, refreshToken, UserResponse.from(user));
    }
}
