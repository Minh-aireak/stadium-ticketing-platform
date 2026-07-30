package com.aireak.common.web.advice;

import com.aireak.common.exception.DomainException;
import com.aireak.common.exception.ForbiddenException;
import com.aireak.common.exception.IdentityMismatchException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsDomainExceptionTo422() {
        ProblemDetail problem = handler.handleDomainException(new TestDomainException("bad state"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(problem.getDetail()).isEqualTo("bad state");
        assertThat(problem.getType().toString()).endsWith("domain-error");
    }

    @Test
    void mapsIdentityMismatchTo403() {
        ProblemDetail problem = handler.handleIdentityMismatch(new IdentityMismatchException("not yours"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problem.getType().toString()).endsWith("identity-mismatch");
    }

    @Test
    void mapsForbiddenTo403() {
        ProblemDetail problem = handler.handleForbidden(new ForbiddenException("ADMIN required"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problem.getType().toString()).endsWith("forbidden");
    }

    @Test
    void mapsMissingRequestParameterTo400() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("page", "int");

        ProblemDetail problem = handler.handleMissingParameter(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void mapsConstraintViolationTo400() {
        ProblemDetail problem = handler.handleConstraintViolation(
                new ConstraintViolationException("invalid", Set.of()));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getType().toString()).endsWith("validation-error");
    }

    @Test
    void mapsMalformedJsonTo400WithoutLeakingTheRawMessage() {
        org.springframework.http.HttpInputMessage emptyInputMessage = new org.springframework.http.HttpInputMessage() {
            @Override
            public java.io.InputStream getBody() {
                return java.io.InputStream.nullInputStream();
            }

            @Override
            public org.springframework.http.HttpHeaders getHeaders() {
                return new org.springframework.http.HttpHeaders();
            }
        };
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "Cannot deserialize com.internal.SecretClass field xyz", emptyInputMessage);

        ProblemDetail problem = handler.handleMessageNotReadable(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("Malformed JSON request body");
    }

    @Test
    void mapsDataIntegrityViolationTo409WithoutLeakingTheRawMessage() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_x\"");

        ProblemDetail problem = handler.handleDataIntegrityViolation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).isEqualTo("The request conflicts with existing data");
    }

    @Test
    void mapsMethodArgumentTypeMismatchTo400() throws NoSuchMethodException {
        java.lang.reflect.Method dummyMethod = GlobalExceptionHandlerTest.class
                .getDeclaredMethod("dummyEndpoint", int.class);
        org.springframework.core.MethodParameter methodParameter =
                new org.springframework.core.MethodParameter(dummyMethod, 0);
        var ex = new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                "abc", int.class, "page", methodParameter, new NumberFormatException("abc"));

        ProblemDetail problem = handler.handleTypeMismatch(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).contains("page");
    }

    @SuppressWarnings("unused")
    private void dummyEndpoint(int page) {
    }

    @Test
    void mapsMethodNotSupportedTo405() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("DELETE", Set.of("GET", "POST"));

        ProblemDetail problem = handler.handleMethodNotSupported(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
    }

    @Test
    void mapsMediaTypeNotSupportedTo415() {
        HttpMediaTypeNotSupportedException ex =
                new HttpMediaTypeNotSupportedException("Unsupported content type");

        ProblemDetail problem = handler.handleMediaTypeNotSupported(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
    }

    @Test
    void mapsNoResourceFoundTo404() {
        NoResourceFoundException ex = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/api/v1/unknown", "No resource found");

        ProblemDetail problem = handler.handleNoRouteFound(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getType().toString()).endsWith("not-found");
    }

    @Test
    void mapsIllegalArgumentTo400() {
        ProblemDetail problem = handler.handleIllegalArgument(new IllegalArgumentException("seat codes must be unique"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("seat codes must be unique");
    }

    @Test
    void mapsUnexpectedExceptionTo500WithoutLeakingTheRawMessage() {
        ProblemDetail problem = handler.handleGenericException(new RuntimeException("db connection string leaked here"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred");
    }

    private static final class TestDomainException extends DomainException {
        TestDomainException(String message) {
            super(message);
        }
    }
}
