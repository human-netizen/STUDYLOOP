package com.studyloop.backend.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// How long this application is willing to wait for somebody else's server (added 2026-09-11).
//
// **The defect this closes: until now, six of eight HTTP clients had no ceiling of any kind.**
// `RestClient.create()` sets neither a connect nor a read timeout, so a provider that accepts the
// connection and then stops talking blocks the calling thread until the operating system gives up
// on a healthy TCP connection — which is to say never. `GeminiVisionClient` was given timeouts in
// Phase 25.3 and `HttpVideoWorker` had them from the start, with a comment explaining exactly why;
// the same reasoning was one file away from five other clients for three weeks and never applied.
//
// **Why one block rather than a field on each client's own properties record.** A timeout is not a
// fact about Cohere or about Supabase, it is this application's patience, and the numbers are only
// defensible *relative to each other* — the value for an interactive chat turn has to be read
// beside the value for a batch embedding to see whether either makes sense. Keeping them adjacent
// is what makes them reviewable. `VisionProperties.pageTimeout` stays where it is because it is
// genuinely different: it is a per-page budget that drives a *fallback*, not a failure.
//
// **Sized from measurements rather than from caution**, taken 2026-09-11 and in Phase 26:
//   - a grounded chat turn is ~4.7s end to end, of which the generation call is ~2-3s
//   - a video *planning* call on the same client was measured at 60-90s
//   - one Cohere embedding of one short text: 0.89s
//   - the cross-encoder rerank of ~18 passages: ~1.3s
//   - a warm production login, for comparison with a network that is merely slow: 3.6s
//
// **A timeout is never retried**, here or anywhere in this codebase, and that is what makes the
// numbers a real trade rather than a free safety net: the provider may have completed the work and
// billed for it, so a second send is a second bill. Too tight a value therefore does not make a
// slow call safe, it turns a slow success into a lost one. Every default below is consequently set
// well clear of the slowest *legitimate* call it has to survive, not near the median.
@ConfigurationProperties(prefix = "studyloop.http")
public record HttpProperties(
        // Shared by every client. A TCP handshake to a provider's edge either happens in well under
        // a second or is not going to: nothing is gained by waiting longer, and unlike a read
        // timeout there is no risk of abandoning work that was already done, because no request has
        // been sent yet.
        Duration connectTimeout,
        // **The widest of these, and deliberately so.** The same `CohereChatClient` serves an
        // interactive chat turn (~2-3s) and a video scene planner (60-90s measured), so the ceiling
        // has to clear the planner. 180s is twice the slowest planning call seen, and it still sits
        // inside Phase 21's 3-minute per-scene budget, so a hung planner fails the scene rather than
        // the job. An interactive turn would prefer something far tighter; splitting the two is a
        // second client and a second set of credentials, which is not worth it to improve a number
        // that only matters when a provider has already stopped responding.
        Duration chatReadTimeout,
        // Ingestion embeds in batches, so this is a whole batch rather than one text, and the cost
        // scales with the batch. 0.89s for a single short text leaves an enormous margin at 120s —
        // which is the point: an embedding call that is slow is usually a large batch doing its job.
        Duration embeddingReadTimeout,
        // ~1.3s measured, and it sits on the critical path of every question, so the patience here
        // buys nothing after the first few seconds. 30s is already twenty times the observed cost;
        // a rerank slower than that has failed whatever it reports.
        Duration rerankReadTimeout,
        // Object storage moves whole PDFs both ways, so this one is bounded by bandwidth rather than
        // by anybody's model. 120s carries a large lecture deck over a slow uplink; it is the only
        // value here that a user's own connection can legitimately push toward the limit.
        Duration storageReadTimeout) {

    // For tests and for any construction site that does not care: the shipped defaults, so a test
    // never has to invent timeout values to exercise something unrelated to them.
    public static HttpProperties defaults() {
        return new HttpProperties(Duration.ofSeconds(5), Duration.ofSeconds(180),
                Duration.ofSeconds(120), Duration.ofSeconds(30), Duration.ofSeconds(120));
    }
}
