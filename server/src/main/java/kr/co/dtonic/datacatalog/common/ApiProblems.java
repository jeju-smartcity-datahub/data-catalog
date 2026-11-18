package kr.co.dtonic.datacatalog.common;

import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ApiProblems {
    private final ObjectMapper mapper;

    public ApiProblems(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ProblemDetail create(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return problem;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), create(status, code, detail, request));
    }
}
