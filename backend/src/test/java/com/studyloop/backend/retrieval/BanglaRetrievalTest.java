package com.studyloop.backend.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.Document;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.DocumentRepository;
import com.studyloop.backend.document.DocumentStatus;
import com.studyloop.backend.document.Language;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.retrieval.eval.BanglaGoldenSet;
import com.studyloop.backend.retrieval.eval.BanglaGoldenSet.BanglaQuestion;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 19.2 and 19.4 — what each lexical retriever finds for a Bangla question, measured rather
// than argued, and reported apart from the English set.
//
// **The plan for 19.2 said to give `content_tsv` a per-language text search configuration, and
// measuring first showed there is nothing for that to do.** `to_tsvector('english', …)` and
// `to_tsvector('simple', …)` produce byte-identical output on Bangla text — checked against this
// database before a line of it was written — because the English stemmer and the English stopword
// list are ASCII rules that no Bengali word can trigger. The config was never what broke Bangla
// retrieval. Two other things are, and both are visible in the table this test prints:
//
//   1. **The AND.** `plainto_tsquery` joins every word it does not recognise as a stopword, and its
//      stopword list is ASCII — so "কি", "কত", "কীভাবে" all survive into the query and every one of
//      them has to be present in the chunk. A Bangla question AND-joins *more* terms than an
//      English question of the same length, and the extra ones carry no meaning.
//   2. **The case suffix.** Bangla marks case by attaching a suffix to the noun, so a student
//      asking about "কুইকসর্টের" is asking about a page that says "কুইকসর্ট". Those are two
//      lexemes under every text search configuration Postgres has, because Postgres has no Bangla
//      stemmer. No lexeme retriever can match them. The trigram list scores that pair at 0.889 —
//      which is 18.1 paying for itself in a language it was not built for.
//
// It runs on every build rather than behind a system property: it needs a database and no provider,
// and thirty SQL queries against twelve chunks is not a cost worth gating.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class BanglaRetrievalTest {

    private static final int K = 6;
    private static final int MAX_TERMS = 8;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ChunkSearchRepository searchRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID actorId;
    private UUID courseId;
    private UUID documentId;

    @BeforeEach
    void ingestTheBanglaCourse() throws Exception {
        User owner = saveUser();
        actorId = owner.getId();
        String token = jwtService.generateAccessToken(owner);
        courseId = UUID.fromString(createCourse(token));
        documentId = upload(token);
        ingestionService.ingest(documentId);
    }

    @Test
    void aBanglaDocumentIsDetectedAsBanglaOnTheWayIn() {
        Document document = documentRepository.findById(documentId).orElseThrow();

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(document.getLanguage())
                .as("19.1 — detected from the extracted text, not from the filename or the upload")
                .isEqualTo(Language.BANGLA);
    }

    @Test
    void theAndFormOfTheLexicalHalfFindsAlmostNothingAndTheOtherTwoDo() {
        Result and = run(Retriever.LEXEME_AND);
        Result or = run(Retriever.LEXEME_OR);
        Result trigram = run(Retriever.TRIGRAM);

        System.out.println(render(and, or, trigram));

        // The premise: the corpus is there, so a zero below is a retriever's answer and not an
        // ingestion failure.
        assertThat(chunkCount()).isGreaterThan(0);
        // The defect. Every question in the set is answerable from a page of this document, and the
        // shipped lexical half returns nothing at all for most of them.
        assertThat(and.emptyResults())
                .as("AND-ing a Bangla question's words matches almost no chunk")
                .isGreaterThan(BanglaGoldenSet.QUESTIONS.size() / 2);
        // 19.2's repair and 18.1's, each measured on the same pages. Both are asserted as
        // relationships rather than as numbers: the numbers belong to this fixture, the ordering
        // belongs to the query forms and to the shape of the language.
        assertThat(or.emptyResults()).isLessThan(and.emptyResults());
        assertThat(or.recall()).isGreaterThan(and.recall());
        assertThat(trigram.emptyResults()).isZero();
        assertThat(trigram.recall()).isGreaterThan(and.recall());
    }

    @Test
    void aCaseSuffixIsInvisibleToALexemeIndexAndNotToTrigrams() {
        // The single sharpest fact about Bangla retrieval, as one question. "কুইকসর্টের" is
        // "quicksort's"; the page says "কুইকসর্ট". No configuration Postgres ships reduces those to
        // one lexeme, under AND or under OR, because the reduction would need a Bangla stemmer.
        String suffixed = "কুইকসর্টের";
        String bare = "কুইকসর্ট";

        assertThat(searchRepository.fullTextSearch(courseId, actorId, suffixed, K))
                .as("a lexeme index cannot see through a Bangla case suffix")
                .isEmpty();
        assertThat(searchRepository.fullTextSearch(courseId, actorId, suffixed, K, true))
                .as("and OR-ing one term with itself does not help — the lexeme is still wrong")
                .isEmpty();
        assertThat(searchRepository.fullTextSearch(courseId, actorId, bare, K))
                .as("the control: the bare form is in the text and matches")
                .isNotEmpty();

        List<ChunkHit> fuzzy =
                searchRepository.trigramSearch(courseId, actorId, List.of(suffixed), K);
        assertThat(fuzzy).isNotEmpty();
        assertThat(fuzzy.get(0).content()).contains(bare);
    }

    @Test
    void theQuestionTermSplitterHandlesBengaliScript() {
        // QueryTerms splits on anything that is not a letter or a digit and keeps tokens of four
        // characters or more. Bengali letters are letters and Bengali vowel signs are not, so the
        // splitting is correct — but the length floor counts codepoints, and a Bangla word carries
        // combining marks that a Latin word does not. "কত" is two codepoints and dropped as
        // intended; the content words survive.
        List<String> terms = QueryTerms.of(
                "কুইকসর্টের সবচেয়ে খারাপ ক্ষেত্রে সময় জটিলতা কত?", MAX_TERMS);

        assertThat(terms).contains("কুইকসর্টের");
        assertThat(terms).doesNotContain("কত");
    }

    // ── the measurement ─────────────────────────────────────────────────────────────────────

    private enum Retriever { LEXEME_AND, LEXEME_OR, TRIGRAM }

    private record Result(String label, int questions, int emptyResults, double recall, double mrr) {
    }

    private Result run(Retriever retriever) {
        int found = 0;
        int empty = 0;
        double reciprocalSum = 0;
        for (BanglaQuestion question : BanglaGoldenSet.QUESTIONS) {
            List<ChunkHit> hits = switch (retriever) {
                case LEXEME_AND -> searchRepository.fullTextSearch(
                        courseId, actorId, question.question(), K);
                case LEXEME_OR -> searchRepository.fullTextSearch(
                        courseId, actorId, question.question(), K, true);
                case TRIGRAM -> {
                    List<String> terms = QueryTerms.of(question.question(), MAX_TERMS);
                    yield terms.isEmpty() ? List.of()
                            : searchRepository.trigramSearch(courseId, actorId, terms, K);
                }
            };
            if (hits.isEmpty()) {
                empty++;
            }
            // One relevant page per question, so recall@k is "did it come back" and the reciprocal
            // rank is 1/position. Simpler than the English harness's graded spans because these
            // passages are short and each answers exactly one question — inventing a span here
            // would be inventing an ambiguity the fixture does not have.
            int rank = rankOf(hits, question.expectedPage());
            if (rank > 0) {
                found++;
                reciprocalSum += 1.0 / rank;
            }
        }
        int total = BanglaGoldenSet.QUESTIONS.size();
        return new Result(retriever.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                total, empty, (double) found / total, reciprocalSum / total);
    }

    private static int rankOf(List<ChunkHit> hits, int expectedPage) {
        for (int i = 0; i < hits.size(); i++) {
            Integer page = hits.get(i).pageNumber();
            if (page != null && page == expectedPage) {
                return i + 1;
            }
        }
        return 0;
    }

    private String render(Result... results) {
        StringBuilder out = new StringBuilder(
                "\n===== Bangla lexical retrieval (19.2 / 19.4) =====\n");
        out.append("corpus        1 document / %d chunks, authored for this test%n"
                .formatted(chunkCount()));
        out.append("questions     %d Bangla, one answering page each%n"
                .formatted(BanglaGoldenSet.QUESTIONS.size()));
        out.append("k             %d   (one retriever alone: no dense list, no fusion, no rerank)%n%n"
                .formatted(K));
        out.append(String.format(Locale.ROOT, "%-12s %8s %8s %10s%n",
                "retriever", "recall", "MRR", "no hits"));
        for (Result result : results) {
            out.append(String.format(Locale.ROOT, "%-12s %8.3f %8.3f %6d/%-3d%n",
                    result.label(), result.recall(), result.mrr(),
                    result.emptyResults(), result.questions()));
        }
        out.append("""

                Reported apart from the English golden set on purpose: ten questions folded into
                fifty-six move a mean by a sixth of whatever they do, which is inside the noise of
                every stage measured so far. The corpus is authored here rather than taken from a
                real Bangla textbook, so these numbers describe the mechanism — what an AND-joined
                lexeme query does to a Bangla question — and not how well a real Bangla course
                would be served.
                """);
        return out.toString();
    }

    private int chunkCount() {
        Integer count = jdbc.queryForObject(
                "select count(*) from document_chunks where document_id = ?", Integer.class,
                documentId);
        return count == null ? 0 : count;
    }

    // ── fixture ─────────────────────────────────────────────────────────────────────────────

    private UUID upload(String token) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "তথ্য-কাঠামো.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                BanglaGoldenSet.docx());
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private String createCourse(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("তথ্য কাঠামো ও অ্যালগরিদম", null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("bangla-retrieval-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Bangla Retrieval Test User");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }
}
