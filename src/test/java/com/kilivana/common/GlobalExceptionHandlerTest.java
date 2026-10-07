package com.kilivana.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.kilivana.common.api.GlobalExceptionHandler;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unexpectedExceptionLogsReferenceAndStackTraceButReturnsSanitizedError() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var request = new MockHttpServletRequest("GET", "/api/v1/products/mine");
            var failure = new IllegalStateException("internal detail", new RuntimeException("deepest cause"));
            var response = handler.handleUnexpected(failure, request);
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
            assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.getBody().requestId()).isNotBlank();
            assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(response.getBody().requestId());
            assertThat(appender.list).hasSize(1);
            var event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
            assertThat(event.getFormattedMessage()).contains(response.getBody().requestId());
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
            assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
            assertThat(event.getThrowableProxy().getCause().getMessage()).isEqualTo("deepest cause");
            assertThat(response.getBody().toString()).doesNotContain("internal detail", "deepest cause", "IllegalStateException");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void duplicateKeysAndOptimisticLocksUseSafeErrors() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.addHeader("X-Request-Id", "error-1");

        var duplicate = handler.handleDataIntegrity(
                new DataIntegrityViolationException("jdbc password secret"),
                request);
        var optimistic = handler.handleOptimisticLock(
                new OptimisticLockingFailureException("version secret"),
                request);

        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody().message()).doesNotContain("jdbc", "password", "secret");
        assertThat(optimistic.getStatusCode().value()).isEqualTo(409);
        assertThat(optimistic.getBody().message()).doesNotContain("version");
    }

    @Test
    void multipartLimitUsesA413Envelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/inspections/1/photos");

        var response = handler.handleMaxUpload(new MaxUploadSizeExceededException(10L), request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody().code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(response.getBody().requestId());
    }
}
