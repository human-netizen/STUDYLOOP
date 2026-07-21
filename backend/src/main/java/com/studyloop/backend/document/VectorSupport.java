package com.studyloop.backend.document;

// Shared helper for talking to the pgvector column, which is unmapped in JPA. pgvector accepts
// a "[f1,f2,...]" text literal that we cast to `vector` in native SQL; Float.toString always
// emits a locale-safe dot so the literal parses regardless of the JVM locale.
public final class VectorSupport {

    private VectorSupport() {
    }

    public static String toLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }
}
