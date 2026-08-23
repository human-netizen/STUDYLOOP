package com.studyloop.backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.Language;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.document.StubAiConfig.RecordingChatClient;
import com.studyloop.backend.chat.dto.ChatRequest;
import com.studyloop.backend.retrieval.eval.BanglaGoldenSet;
import com.studyloop.backend.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 19.3, the half of it that decides what the student actually reads: the language the answer
// comes back in.
//
// **The bug was not that the model ignored Bangla — it was that the model was told to write
// English and did.** Every instruction in the system prompt is English, and a model answers in the
// language it is instructed in, so a Bangla question about a Bangla document produced a fluent
// English answer citing the right pages. Nothing failed, nothing was logged, and no retrieval
// metric moved, which is why this needed a test rather than a look.
//
// **The refusal is the other half and the easier one to forget.** It never reaches a provider — it
// is a constant, chosen by the gate — so no amount of prompt work touches it. A student who asks in
// Bangla and is told in English that their question is not in the materials has been answered by a
// system that did not read the question.
//
// The four annotations match SemanticCacheTest, ForumEscalationTest and RerankPipelineTest exactly,
// which is deliberate: Spring caches one ApplicationContext per distinct configuration and each one
// opens its own Hikari pool against a session pooler that allows fifteen clients in total. An
// annotation set that differs by one line is a fifth pool, and the suite pays for it in a class
// that has nothing to do with this one.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class BanglaAnswerTest {

    // From the fixture's page 5, which the corpus can answer.
    private static final String BANGLA_QUESTION =
            "বাইনারি সার্চ ট্রিতে অনুসন্ধানের সবচেয়ে খারাপ সময় জটিলতা কত?";
    // Nothing in a data-structures course answers this, in any language.
    private static final String BANGLA_OFF_TOPIC = "ঢাকার আবহাওয়া আগামীকাল কেমন থাকবে?";
    private static final String ENGLISH_QUESTION = "What is the worst case cost of a search tree?";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private RecordingChatClient chatClient;

    @Autowired
    private StubAiConfig.StubRerankClient reranker;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;
    private String courseId;

    @BeforeEach
    void ingestTheBanglaCourse() throws Exception {
        User owner = saveUser();
        token = jwtService.generateAccessToken(owner);
        courseId = createCourse();
        uploadAndIngest();
        chatClient.reset();
        reranker.reset();
    }

    @AfterEach
    void resetSharedStubs() {
        // Both beans are shared with every other class in this context and are off by default
        // there. Leaving the reranker configured would change the pipeline under tests written
        // against the fused one, in an order-dependent way that only appears in a full-suite run.
        chatClient.reset();
        reranker.reset();
    }

    @Test
    void aBanglaQuestionInstructsTheModelToAnswerInBangla() throws Exception {
        // The gate is what stands between a question and a prompt, and with a stub embedder a
        // Bangla question sits nowhere near its page in vector space. So the cross-encoder is told
        // the passage is relevant rather than a fixture being built to make it look relevant —
        // this test is about the prompt, and the gate has its own tests.
        reranker.configured = true;
        reranker.forcedRelevance = 0.9;

        ask(BANGLA_QUESTION);

        assertThat(chatClient.lastSystemPrompt())
                .as("the model has to be told, or it answers in the language it was instructed in")
                .contains("Write your answer in " + Language.BANGLA.promptName());
        // Named in English inside an otherwise English prompt: an instruction written in Bangla is
        // one more line the model can read as material to answer rather than as an instruction.
        assertThat(chatClient.lastSystemPrompt()).contains("Bangla");
    }

    @Test
    void anEnglishQuestionsPromptIsExactlyWhatItWasBefore() throws Exception {
        reranker.configured = true;
        reranker.forcedRelevance = 0.9;

        ask(ENGLISH_QUESTION);

        // Not "contains English" — *absent*. Every answer this application has produced came out of
        // the prompt without this line in it, and adding one for the common case would change what
        // every English answer says in exchange for nothing.
        assertThat(chatClient.lastSystemPrompt()).doesNotContain("Write your answer in");
        assertThat(chatClient.lastSystemPrompt())
                .startsWith("You are StudyLoop's study assistant.")
                .contains("- Be concise and precise.\n\nSources:");
    }

    @Test
    void aRefusalIsWrittenInTheLanguageTheQuestionWasAskedIn() throws Exception {
        // No reranker, so the Phase 5.3 rule applies: a weak cosine and no lexical hit. An
        // off-topic question in either language satisfies both.
        JsonNode bangla = ask(BANGLA_OFF_TOPIC);
        JsonNode english = ask("What is the capital city of France?");

        assertThat(bangla.get("answer").asText())
                .as("the refusal never reaches a provider, so nothing else could have translated it")
                .contains("খুঁজে পাইনি");
        assertThat(english.get("answer").asText())
                .isEqualTo("I don't have that in this course's materials. Try rephrasing, or "
                        + "upload a document that covers it.");
        assertThat(chatClient.calls.get())
                .as("a refusal is deterministic and free in both languages")
                .isZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private JsonNode ask(String question) throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses/" + courseId + "/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChatRequest(question, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void uploadAndIngest() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "তথ্য-কাঠামো.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                BanglaGoldenSet.docx());
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        ingestionService.ingest(UUID.fromString(objectMapper.readTree(body).get("id").asText()));
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("তথ্য কাঠামো", null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("bangla-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Bangla Test User");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }
}
