package com.studyloop.backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyloop.backend.auth.Role;
import com.studyloop.backend.auth.User;
import com.studyloop.backend.auth.UserRepository;
import com.studyloop.backend.chat.dto.ChatRequest;
import com.studyloop.backend.course.dto.CreateCourseRequest;
import com.studyloop.backend.document.CountingDataSource;
import com.studyloop.backend.document.DocumentIngestionService;
import com.studyloop.backend.document.StubAiConfig;
import com.studyloop.backend.security.JwtService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Phase 26.2 — what one chat turn costs in round trips, asserted rather than hoped for.
//
// Before this phase a turn made twelve to sixteen database calls and two provider calls before
// generation started, strictly serially, against a Supabase pooler in ap-northeast-1. Each query
// was indexed and each was fast; there were simply a lot of them, and on a wide-area link the
// count *is* the latency. The budget below is the ceiling that number is not allowed to drift back
// through.
//
// **It is a ceiling, not an equality.** A number pinned exactly would fail on every unrelated
// change — a new column, a Hibernate version that flushes differently — and a test that fails for
// reasons nobody caused gets deleted rather than fixed. What must never happen is the count
// climbing, and a ceiling says exactly that.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(StubAiConfig.class)
class ChatTurnCostTest {

    // Measured rather than guessed: an opening turn sends **nine** statements on this fixture and
    // a follow-up sends **seven**, where before this phase the same turn sent twelve to sixteen.
    // The nine are the membership check, the conversation insert, the cache probe's nearest
    // neighbour, the question's insert, the three candidate lists in one union, the section
    // expansion in one `in`, the recurrence scan, and the question log with its document rows.
    // The ceiling leaves one statement of slack, which is enough for the log to grow a row and not
    // enough for a query to be added without somebody noticing.
    private static final int OPENING_TURN_BUDGET = 10;

    // **Every content word of these is in the ingested text, and that is not decoration.**
    // `plainto_tsquery` AND-joins the words it does not recognise as stopwords (19.2's lexical-or
    // ships off), so a question carrying one word the corpus lacks returns *nothing* from the
    // lexical half; the stub embedder's vectors are near-orthogonal noise, so the dense half
    // cannot rescue it; and the confidence gate then refuses. A refused turn makes fewer round
    // trips and builds no prompt, so it would pass the budget test while proving nothing.
    private static final String MERGE_QUESTION = "How does merge sort merge the sorted halves?";
    private static final String PIVOT_QUESTION = "What is the randomly chosen pivot element?";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private ChatService chatService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;
    private UUID actorId;
    private UUID courseId;

    @BeforeEach
    void ingestACorpus() throws Exception {
        User owner = saveUser();
        actorId = owner.getId();
        token = jwtService.generateAccessToken(owner);
        courseId = UUID.fromString(createCourse());
        ingestPdf("sorting.pdf",
                "Merge sort splits the array in half and merges the sorted halves.",
                "Quicksort partitions the array around a randomly chosen pivot element.");
    }

    @AfterEach
    void stopCounting() {
        CountingDataSource.stop();
    }

    @Test
    void anOpeningTurnStaysInsideItsRoundTripBudget() {
        CountingDataSource.start();
        chatService.prepare(actorId, courseId, new ChatRequest(PIVOT_QUESTION, null));
        int statements = CountingDataSource.stop();

        assertTrue(statements <= OPENING_TURN_BUDGET,
                () -> "an opening chat turn sent " + statements + " statements, budget is "
                      + OPENING_TURN_BUDGET + " — see Phase 26.2 before raising this");
    }

    // Continuing a thread must not cost *more* than opening one. It used to: the ownership check
    // and the transcript were two reads where they are now one join, and the second turn paid for
    // both.
    @Test
    void continuingAThreadCostsNoMoreThanOpeningOne() {
        PreparedTurn first = chatService.prepare(actorId, courseId,
                new ChatRequest(MERGE_QUESTION, null));

        CountingDataSource.start();
        chatService.prepare(actorId, courseId,
                new ChatRequest(PIVOT_QUESTION, first.conversationId()));
        int statements = CountingDataSource.stop();

        assertTrue(statements <= OPENING_TURN_BUDGET,
                () -> "a follow-up sent " + statements + " statements, budget is "
                      + OPENING_TURN_BUDGET);
    }

    // The reordering has to leave the transcript exactly as it was: the question is saved during
    // the read phase and appended to the prompt by hand, rather than being read back out of the
    // history it was just written into.
    @Test
    void theQuestionIsTheLastMessageTheModelSees() {
        PreparedTurn prepared = chatService.prepare(actorId, courseId,
                new ChatRequest(PIVOT_QUESTION, null));

        assertFalse(prepared.messages().isEmpty(),
                () -> "expected a grounded turn; an empty prompt means the gate refused");
        LlmMessage last = prepared.messages().get(prepared.messages().size() - 1);
        assertEquals("user", last.role());
        assertEquals(PIVOT_QUESTION, last.content());
        assertEquals("system", prepared.messages().get(0).role());
    }

    // And a follow-up still replays the thread: the earlier question and its answer are in front of
    // the new one, in order.
    @Test
    void aFollowUpReplaysTheThreadBeforeTheNewQuestion() {
        PreparedTurn first = chatService.prepare(actorId, courseId,
                new ChatRequest(MERGE_QUESTION, null));
        chatService.completeTurn(first, "Merge sort splits the array [1].");

        PreparedTurn second = chatService.prepare(actorId, courseId,
                new ChatRequest(PIVOT_QUESTION, first.conversationId()));

        List<String> conversation = new ArrayList<>();
        for (LlmMessage message : second.messages()) {
            conversation.add(message.role() + ": " + message.content());
        }
        assertTrue(conversation.contains("user: " + MERGE_QUESTION),
                () -> "the earlier question should replay, got " + conversation);
        assertTrue(conversation.contains("assistant: Merge sort splits the array [1]."),
                () -> "the earlier answer should replay, got " + conversation);
        assertEquals("user: " + PIVOT_QUESTION, conversation.get(conversation.size() - 1));
    }

    private User saveUser() {
        User user = new User();
        user.setEmail("cost-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-hash");
        user.setDisplayName("Cost Probe");
        user.setRole(Role.USER);
        return userRepository.saveAndFlush(user);
    }

    private String createCourse() throws Exception {
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCourseRequest("Sorting", "desc"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void ingestPdf(String filename, String... lines) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/courses/" + courseId + "/documents")
                        .file(new MockMultipartFile("file", filename, "application/pdf", pdfBytes(lines)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        ingestionService.ingest(UUID.fromString(objectMapper.readTree(body).get("id").asText()));
    }

    private byte[] pdfBytes(String... lines) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                for (String line : lines) {
                    content.showText(line);
                    content.newLineAtOffset(0, -16);
                }
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
