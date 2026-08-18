package com.studyloop.backend.document;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

// Counts tokens with a real byte-pair encoder instead of guessing at them (Phase 13.1).
//
// What it replaces: `chars / 4`, which every text budget in this application was built on — the
// chunk ceiling, the quiz material cap, the summary material cap. That estimate is roughly 15%
// wrong for English prose and much worse for anything it was never fitted to: code, tables of
// numbers, LaTeX, and Bangla, where a single character can cost several tokens. A ceiling built on
// it is not a ceiling, and Phase 13's whole premise is that 500 tokens means 500 tokens.
//
// Two honest caveats, in the order they matter:
//
//   1. This is cl100k_base — OpenAI's encoder — and the provider is Cohere, whose models use their
//      own BPE. So the count is not exact for the model that eventually reads the text. It is a
//      different tokenizer trained on similar data, which lands within a few percent on English
//      prose; the alternative is a network round trip to Cohere's /tokenize per chunk at ingest,
//      which would put a provider outage in the middle of chunking. Being off by a few percent on
//      a ceiling is fine. Being off by an unknown amount, which is what chars/4 was, is not.
//   2. Encoding is not free — roughly a microsecond per hundred characters. That is nothing at
//      ingest (once per chunk, next to an HTTP embedding call) and it is why nothing calls this
//      per request.
@Component
public class TokenCounter {

    // Lazy: the encoder's vocabulary is a megabyte of resource data, and a test that never touches
    // a token should not pay to load it. Thread-safe once built, which is what lets one bean serve
    // every ingestion thread.
    private final Encoding encoding =
            Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    public int count(String text) {
        return text == null || text.isEmpty() ? 0 : encoding.countTokens(text);
    }
}
