package com.studyloop.backend.review;

import java.util.UUID;

// The card isn't in the caller's review queue — it doesn't exist, or it belongs to someone else.
// Both read the same way from outside, so ownership isn't leaked.
public class ReviewCardNotFoundException extends RuntimeException {

    public ReviewCardNotFoundException(UUID cardId) {
        super("No review card with id " + cardId + ".");
    }
}
