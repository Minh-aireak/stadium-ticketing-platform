package com.aireak.gateway.util;

import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes a {@link ProblemDetail} the way the rest of the platform's error responses are
 * serialized.
 *
 * <p>The gateway's filters write their own bodies straight to the response rather than returning a
 * value for a message converter to render, so they need their own {@link JsonMapper} — and a bare
 * one gets {@link ProblemDetail} wrong. Its extension members live in a
 * {@code Map} behind {@code getProperties()}, which Jackson serializes as a nested object:
 *
 * <pre>{@code {"status":401, ..., "properties":{"timestamp":"..."}} }</pre>
 *
 * <p>RFC 7807 puts extension members at the top level, and that is where every backend service's
 * responses have them, because Spring's message converter applies
 * {@link ProblemDetailJacksonMixin} — the {@code @JsonAnyGetter}/{@code @JsonAnySetter} pair that
 * flattens the map. A client reading {@code detail} sees no difference; one reading
 * {@code timestamp} or {@code correlationId} sees a field that is not where it is on any other
 * response the platform issues.
 *
 * <p>One copy here rather than one per filter, so the three call sites cannot drift apart on it
 * again.
 */
public final class ProblemDetailJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

    private ProblemDetailJson() {
    }

    /** The RFC 7807 JSON for {@code problem}, extension members flattened to the top level. */
    public static byte[] toBytes(ProblemDetail problem) {
        return MAPPER.writeValueAsBytes(problem);
    }
}
