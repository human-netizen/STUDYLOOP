package com.studyloop.backend.config;

import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// One place that knows how to build a `RestClient` which will eventually give up (2026-09-11).
//
// `RestClient.create()` is the trap this exists to remove: it reads as a complete construction and
// sets no connect timeout and no read timeout, so every client written that way inherits an
// unbounded wait without anything in the source looking wrong. Six of this application's eight
// clients were written that way. A named factory makes the timed version the *shorter* thing to
// type, which is the only reliable way to make it the thing that gets typed.
//
// `SimpleClientHttpRequestFactory` rather than a pooled client, matching `GeminiVisionClient` and
// `HttpVideoWorker`: these are a handful of calls per request against a few hosts, so connection
// pooling would add a dependency and a tuning surface to solve a problem this application does not
// have.
public final class TimedRestClient {

    private TimedRestClient() {
    }

    public static RestClient with(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    // Same thing for a client that also wants a fixed base URL, so `OllamaEmbeddingClient` does not
    // have to choose between a base URL and a timeout — which, as written, it did.
    public static RestClient with(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
