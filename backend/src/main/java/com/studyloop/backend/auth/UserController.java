package com.studyloop.backend.auth;

import com.studyloop.backend.auth.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    // Phase 27.4. On /me and nowhere else: an endpoint that takes a user id is an endpoint whose
    // whole safety is one equality check, and there is no case in this application for deleting
    // somebody else's account.
    //
    // Refused with 409 while the caller is the last owner of any course - see
    // AccountDeletionService for what the delete does and, more to the point, what it keeps.
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(Authentication authentication) {
        userService.deleteAccount(UUID.fromString(authentication.getName()));
        return ResponseEntity.noContent().build();
    }
}
