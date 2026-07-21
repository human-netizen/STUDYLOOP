package com.studyloop.backend.auth;

import com.studyloop.backend.auth.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // The JWT filter stores the token subject (the user's id) as the Authentication name.
    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        return userService.getById(UUID.fromString(authentication.getName()));
    }
}
