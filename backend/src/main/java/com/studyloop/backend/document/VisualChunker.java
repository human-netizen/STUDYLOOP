package com.studyloop.backend.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Turns the pages an extractor called pictures into chunks (Phase 17.2).
//
// **The text half comes from the page, not from a second provider call, and that is the phase's one
// deviation from its plan.** 17.2 specifies the VLM's transcription. On the pages Phase 15 routed
// it is exactly that, because the router already replaced those pages' text with what the model
// read — so a scanned diagram arrives here described. On the pages it did not route, the text is
// PDFBox's: the caption, the axis labels and the prose around the figure. Paying a vision call to
// re-read a page whose text Phase 15's gate deliberately trusted would undo that phase's entire
// cost argument, and it would spend it on the half of this chunk that is not doing the retrieving.
//
// A page with a picture and no words at all still becomes a chunk, with the document's title as its
// text. It has to: dropping it would make the one kind of page this phase exists for — a figure
// with nothing written near it — the one kind it cannot index.
@Component
@RequiredArgsConstructor
public class VisualChunker {

    private final TokenCounter tokenCounter;

    // One chunk per image, indexed from `startIndex` so they follow the document's text chunks.
    //
    // `pages` is the extracted text, and the lookup is by page number rather than by position
    // because an extractor is free to skip a page it could not read at all.
    public List<VisualChunk> chunk(List<PageImage> images, List<PageText> pages, String title,
                                   int startIndex) {
        if (images.isEmpty()) {
            return List.of();
        }
        Map<Integer, String> textByPage = new HashMap<>();
        for (PageText page : pages) {
            textByPage.put(page.pageNumber(), page.text());
        }

        List<VisualChunk> chunks = new ArrayList<>(images.size());
        int index = startIndex;
        for (PageImage image : images) {
            String content = contentFor(image, textByPage.get(image.pageNumber()), title);
            chunks.add(new VisualChunk(index++, image.pageNumber(), content,
                    tokenCounter.count(content), image.png()));
        }
        return List.copyOf(chunks);
    }

    // What the generator will be shown when this page is retrieved by its picture.
    //
    // The heading is not decoration. A visual chunk carries no section path — it is a page rather
    // than a section, and giving it one would make Phase 13.5's expansion splice a whole section
    // around a figure — so the one line naming the document and the page is all the context the
    // model gets about where this passage came from.
    private static String contentFor(PageImage image, String pageText, String title) {
        String heading = "%s — page %d".formatted(
                title == null || title.isBlank() ? "Figure" : title, image.pageNumber());
        String body = pageText == null ? "" : pageText.strip();
        return body.isEmpty() ? heading : heading + "\n\n" + body;
    }
}
