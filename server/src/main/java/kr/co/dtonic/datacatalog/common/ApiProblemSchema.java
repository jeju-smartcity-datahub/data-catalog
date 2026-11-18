package kr.co.dtonic.datacatalog.common;

import java.net.URI;
import io.swagger.v3.oas.annotations.media.Schema;

/** Documents the JSON representation of Spring ProblemDetail including our extension fields. */
@Schema(name = "ApiProblem")
public record ApiProblemSchema(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) URI type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String detail,
        URI instance,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String requestId) {}
