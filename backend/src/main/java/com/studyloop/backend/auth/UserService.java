package com.studyloop.backend.auth;

import com.studyloop.backend.auth.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final AccountDeletionService accountDeletionService;

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return userRepository.findById(id)
                .map(UserResponse::from)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listAll() {
        return userRepository.findAll().stream()
                .map(UserResponse::from)
                .toList();
    }

    // Phase 27.4. Thin on purpose: what deleting an account actually means is a page of reasoning
    // and lives in AccountDeletionService, and putting it here would bury it under two lookups.
    @Transactional
    public void deleteAccount(UUID id) {
        accountDeletionService.delete(id);
    }
}
