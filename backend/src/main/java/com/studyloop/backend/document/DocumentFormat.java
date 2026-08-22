package com.studyloop.backend.document;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

// What StudyLoop will accept as course material, and the one place that decides it (Phase 16).
//
// Before this phase there was a single string constant compared against `file.getContentType()`
// in DocumentService, and "which formats do we support" was answerable only by reading that
// comparison. Three formats later that is a list, and a list that two callers need: the upload
// endpoint rejects with 415 before storing anything, and the ingestion pipeline picks an extractor
// from the same enum afterwards. Two independent lists would eventually disagree, and the way that
// disagreement surfaces is a file that uploads cleanly and then fails ingestion with "unsupported
// type" — a 202 followed by a FAILED document, for a rule the user could have been told up front.
//
// **The content type is not trusted on its own.** A browser reports .pptx as its OOXML type when it
// knows the extension and as `application/octet-stream` when it does not; curl sends nothing at all
// unless told to. So the extension is checked too, and either one matching is enough. Neither is
// evidence about the *bytes* — a renamed .zip still fails, but it fails in the extractor with a
// message about the file being unreadable, which is the truthful place for it.
public enum DocumentFormat {

    PDF("application/pdf", "pdf"),

    // The OOXML types, which are these two long strings and nothing shorter. Word and PowerPoint
    // both also answer to `application/zip` and `application/octet-stream` in the wild, which is
    // why the extension is a peer of the content type here rather than a fallback.
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),

    // Photographed notes (16.3). A phone camera produces JPEG; a screenshot or a scanner app
    // produces PNG. Both go to the same vision reader, which is given the bytes and the type.
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg", "jpeg");

    // The pre-2007 binary Office formats, named so the refusal can say what is wrong rather than
    // "unsupported". They are a completely different container — OLE2 compound files, read by a
    // different POI module — and half-supporting them means a .ppt that ingests with its speaker
    // notes silently missing. Telling somebody to press Save As is a better outcome than that.
    private static final Set<String> LEGACY_OFFICE_TYPES = Set.of(
            "application/vnd.ms-powerpoint", "application/msword");
    private static final Set<String> LEGACY_OFFICE_EXTENSIONS = Set.of("ppt", "doc");

    private final String contentType;
    private final List<String> extensions;

    DocumentFormat(String contentType, String... extensions) {
        this.contentType = contentType;
        this.extensions = List.of(extensions);
    }

    // The canonical type stored on the document row, which is deliberately not whatever the client
    // sent: a .pptx uploaded as `application/octet-stream` is stored as its real OOXML type, so the
    // download endpoint serves it with a header the browser can act on.
    public String contentType() {
        return contentType;
    }

    public boolean isImage() {
        return this == PNG || this == JPEG;
    }

    // The formats a course manager may upload as material. Images are excluded on purpose: a
    // photograph is a personal note (16.3), it arrives through a different endpoint with different
    // permissions and different visibility, and letting it in here would give it neither.
    public static List<DocumentFormat> materialFormats() {
        return List.of(PDF, PPTX, DOCX);
    }

    public static List<DocumentFormat> imageFormats() {
        return List.of(PNG, JPEG);
    }

    public static Optional<DocumentFormat> of(String contentType, String filename) {
        String type = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        // "image/jpeg; charset=binary" is a legal header and not a format name.
        int parameters = type.indexOf(';');
        if (parameters >= 0) {
            type = type.substring(0, parameters).trim();
        }
        String extension = extensionOf(filename);
        for (DocumentFormat format : values()) {
            if (format.contentType.equals(type) || format.extensions.contains(extension)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    // Resolves a format or refuses the upload. `allowed` is the caller's own list, so the notes
    // endpoint refuses a PDF with the same sentence structure the material endpoint refuses a JPEG.
    public static DocumentFormat require(String contentType, String filename,
                                         List<DocumentFormat> allowed) {
        Optional<DocumentFormat> format = of(contentType, filename);
        if (format.isPresent() && allowed.contains(format.get())) {
            return format.get();
        }
        if (isLegacyOffice(contentType, filename)) {
            throw new UnsupportedDocumentTypeException(
                    "This is a pre-2007 Office file. Open it and use Save As to write a .pptx or "
                            + ".docx, then upload that — the older format stores its text in a "
                            + "different container and would lose speaker notes and headings.");
        }
        throw new UnsupportedDocumentTypeException(
                "Unsupported document type: " + describe(contentType, filename) + ". Accepted: "
                        + allowed.stream().map(DocumentFormat::label).reduce((a, b) -> a + ", " + b)
                                .orElse("nothing") + ".");
    }

    private static boolean isLegacyOffice(String contentType, String filename) {
        String type = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        return LEGACY_OFFICE_TYPES.contains(type)
                || LEGACY_OFFICE_EXTENSIONS.contains(extensionOf(filename));
    }

    private String label() {
        return "." + extensions.get(0);
    }

    private static String describe(String contentType, String filename) {
        if (contentType != null && !contentType.isBlank()) {
            return contentType;
        }
        String extension = extensionOf(filename);
        return extension.isEmpty() ? "unknown" : "." + extension;
    }

    private static String extensionOf(String filename) {
        String base = StringUtils.getFilename(filename);
        if (base == null) {
            return "";
        }
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
