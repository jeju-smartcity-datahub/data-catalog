package kr.co.dtonic.datacatalog.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerSecurityTest {
    private static final String PRIVATE_DETAIL = "jdbc:postgresql://private.invalid/catalog password=not-a-real-secret";
    private final Logger logger = (Logger) LogManager.getLogger(ApiExceptionHandler.class);
    private final CapturingAppender logs = new CapturingAppender();
    private MockMvc mvc;

    @BeforeEach void setup() {
        logs.start(); logger.addAppender(logs);
        mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new ApiExceptionHandler(new ApiProblems(new ObjectMapper()))).build();
    }

    @AfterEach void detachLogCapture() { logger.removeAppender(logs); logs.stop(); }

    @Test void unexpectedRuntimeFailureReturnsFixedProblemAndLogsOnlyExceptionType() throws Exception {
        verifyFailure("/unexpected", 500, "INTERNAL_ERROR");
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getMessage().getFormattedMessage()).isEqualTo("Request failed: type=IllegalStateException");
        assertThat(logs.list.getFirst().getThrown()).isNull();
    }

    @Test void databaseFailureReturnsFixedProblemAndOmitsSqlAndCredentialsFromLog() throws Exception {
        verifyFailure("/database", 503, "CATALOG_UNAVAILABLE");
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getMessage().getFormattedMessage()).isEqualTo("Database request failed: type=DataAccessResourceFailureException");
        assertThat(logs.list.getFirst().getThrown()).isNull();
    }

    @Test void invalidInputIsStillBadRequestWithoutEchoingSubmittedDetails() throws Exception {
        verifyFailure("/invalid", 400, "INVALID_ARGUMENT");
        assertThat(logs.list).isEmpty();
    }

    @Test void expectedBusinessFailureKeepsItsSpecificStatusAndPublicMessage() throws Exception {
        mvc.perform(get("/business")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_CONFLICT"))
                .andExpect(jsonPath("$.detail").value("이미 처리된 요청입니다."));
        assertThat(logs.list).isEmpty();
    }

    private void verifyFailure(String path, int status, String code) throws Exception {
        String body = mvc.perform(get(path).requestAttr(RequestIdFilter.ATTRIBUTE, "security-test-request"))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.requestId").value("security-test-request"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(PRIVATE_DETAIL, "private.invalid", "not-a-real-secret", "stackTrace", "IllegalStateException");
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getMessage().getFormattedMessage()).doesNotContain(PRIVATE_DETAIL));
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> list = new ArrayList<>();
        private CapturingAppender() { super("security-error-capture", null, null, false, Property.EMPTY_ARRAY); }
        @Override public void append(LogEvent event) { list.add(event.toImmutable()); }
    }

    @RestController static class FailureController {
        @GetMapping("/unexpected") void unexpected() { throw new IllegalStateException(PRIVATE_DETAIL, new RuntimeException(PRIVATE_DETAIL)); }
        @GetMapping("/database") void database() { throw new DataAccessResourceFailureException(PRIVATE_DETAIL); }
        @GetMapping("/invalid") void invalid() { throw new IllegalArgumentException(PRIVATE_DETAIL); }
        @GetMapping("/business") void business() { throw new DomainException(HttpStatus.CONFLICT, "TEST_CONFLICT", "이미 처리된 요청입니다."); }
    }
}
