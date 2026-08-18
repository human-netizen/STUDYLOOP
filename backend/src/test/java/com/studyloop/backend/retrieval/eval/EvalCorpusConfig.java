package com.studyloop.backend.retrieval.eval;

import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.CourseSpaceRepository;
import com.studyloop.backend.course.MembershipRepository;
import com.studyloop.backend.document.DocumentChunkRepository;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.DocumentRepository;
import com.studyloop.backend.document.DocumentStorageService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

// Hands EvalCorpus its collaborators for the one test that seeds a corpus. A @TestConfiguration is
// excluded from the application's component scan, so importing it here does not put an eval bean
// into every other Spring context in the suite.
@TestConfiguration
public class EvalCorpusConfig {

    @Bean
    EvalCorpus evalCorpus(UserRepository userRepository,
                          CourseSpaceRepository courseSpaceRepository,
                          MembershipRepository membershipRepository,
                          DocumentRepository documentRepository,
                          DocumentChunkRepository chunkRepository,
                          DocumentStorageService storageService,
                          DocumentIngestionService ingestionService,
                          JdbcTemplate jdbcTemplate) {
        return new EvalCorpus(userRepository, courseSpaceRepository, membershipRepository,
                documentRepository, chunkRepository, storageService, ingestionService, jdbcTemplate);
    }
}
