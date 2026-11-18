package kr.co.dtonic.datacatalog.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("(dev | test) & !prod & !production")
public class OpenApiConfiguration {
    @Bean
    OpenAPI internalOpenApi(@Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName) {
        return new OpenAPI().info(new Info().title("Data Catalog Internal REST API")
                .version("0.2.0").description("실행 중인 내부 REST 계약. 데이터 소비자용 OpenAPI는 검증된 제공 계약에서 별도로 생성한다."))
                .components(new Components().addSecuritySchemes("catalogSession", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE).name(sessionCookieName)
                        .description("서버가 신뢰하는 CatalogPrincipal을 보관한 Spring Security 세션이 필요하다. "
                                + "쿠키는 세션 식별자이며 임의 역할 헤더나 브라우저 역할값은 인증 근거가 아니다. "
                                + "기관·역할·객체 권한은 각 업무에서 추가 검증한다. 실제 SSO 연결은 미구현이다. "
                                + "로컬 로그인은 local-auth 프로필로 활성화하며 prod/production에서는 catalog.local-auth.enabled=true도 필요하다. "
                                + "GET /api/admin/v1/auth에서 사용 여부와 CSRF 토큰을 확인한 후 POST /api/admin/v1/auth/login으로 세션을 발급한다. "
                                + "Basic/Bearer 인증은 제공하지 않는다.")));
    }

    @Bean
    OpenApiCustomizer administrationContract() {
        return document -> document.getPaths().forEach((path, item) -> {
            if (!path.startsWith("/api/admin/")) return;
            item.readOperationsMap().forEach((method, operation) -> {
                boolean bootstrap = method == PathItem.HttpMethod.GET && "/api/admin/v1/auth".equals(path);
                boolean login = method == PathItem.HttpMethod.POST && "/api/admin/v1/auth/login".equals(path);
                // Only these two exact operations permit anonymous access; session and logout remain protected.
                operation.setSecurity(bootstrap || login ? List.of()
                        : List.of(new SecurityRequirement().addList("catalogSession")));
                operation.getResponses().putIfAbsent("401", problemResponse(bootstrap
                        ? "제공된 로컬 세션의 계정·역할·기관 상태 변경으로 세션 무효화; 익명 요청에는 인증 불필요"
                        : "인증된 서버 세션 필요"));
                operation.getResponses().putIfAbsent("503", problemResponse("로컬 세션 재검증 중 인증 저장소 사용 불가"));
                if (!bootstrap) operation.getResponses().putIfAbsent("403", problemResponse("업무·기관·객체 권한 없음 또는 CSRF 토큰 검증 실패"));
                if (login && operation.getResponses().containsKey("429")) {
                    operation.getResponses().get("429").addHeaderObject("Retry-After", new Header()
                            .description("다음 로그인 시도까지 기다릴 초 수.")
                            .schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE)));
                }
                if (!Set.of(PathItem.HttpMethod.GET, PathItem.HttpMethod.HEAD, PathItem.HttpMethod.OPTIONS).contains(method)) {
                    // Spring's default resolver also accepts the _csrf request parameter; the header alone is not mandatory.
                    operation.addParametersItem(new Parameter().in("header").name("X-CSRF-TOKEN").required(false)
                            .schema(new StringSchema()).description("상태 변경 요청은 유효한 CSRF 토큰이 필수다. "
                                    + (login ? "로그인 전에 GET /api/admin/v1/auth 응답의 csrfHeaderName/csrfToken으로 이 헤더를 전송한다. "
                                            : "GET /api/admin/v1/session 응답의 csrfHeaderName/csrfToken으로 이 헤더를 전송한다. ")
                                    + "로그인 성공 후에는 /session, 로그아웃 후에는 /auth에서 새 CSRF 토큰을 조회한다. "
                                    + "Spring 기본 처리의 _csrf 요청 파라미터도 허용되어 헤더 자체는 선택으로 표시한다. "
                                    + "누락·불일치 시 403이며 인증 세션을 대신하지 않는다."));
                }
                if (operation.getParameters() == null) return;
                for (Parameter parameter : operation.getParameters()) {
                    if (!"header".equals(parameter.getIn())) continue;
                    // Controllers accept null to preserve domain-specific errors; services require these existing headers.
                    if ("If-Match".equalsIgnoreCase(parameter.getName())) {
                        parameter.setRequired(true);
                        parameter.setDescription("해당 변경 대상의 조회 응답에서 받은 현재 strong ETag를 그대로 전달한다. "
                                + "누락 시 428, 대상 또는 버전 불일치 시 412. 멱등 키를 함께 쓰는 요청에서 응답 유실 후 "
                                + "재시도할 때는 최초 요청의 ETag와 멱등 키를 유지한다.");
                        operation.getResponses().putIfAbsent("428", problemResponse("If-Match 필요"));
                        operation.getResponses().putIfAbsent("412", problemResponse("대상 또는 버전 충돌"));
                    } else if ("Idempotency-Key".equalsIgnoreCase(parameter.getName())) {
                        parameter.setRequired(true);
                        String pattern = "applyDefinitionImport".equals(operation.getOperationId())
                                ? "[A-Za-z0-9_-]{8,128}" : "[A-Za-z0-9._:-]{8,128}";
                        parameter.setSchema(new StringSchema().minLength(8).maxLength(128).pattern(pattern));
                        parameter.setDescription("요청별 멱등 키. 결과를 받지 못한 동일 요청의 재시도에는 같은 키·본문·조건 헤더를 유지한다. "
                                + "누락·형식 오류 시 400, 같은 키를 다른 요청 내용에 재사용하면 409. 멱등 범위는 해당 업무의 사용자·대상 범위다.");
                        operation.getResponses().putIfAbsent("400", problemResponse("잘못된 입력 또는 멱등 키 누락"));
                        operation.getResponses().putIfAbsent("409", problemResponse("멱등 키 또는 업무 상태 충돌"));
                    }
                }
            });
        });
    }

    private static ApiResponse problemResponse(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType("application/problem+json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiProblem"))));
    }

    @Bean
    OpenApiCustomizer nullableCategoryContract() {
        return document -> {
            // OAS 3.1 uses a null type, not the OAS 3.0 `nullable` keyword.
            Schema<Object> nullSchema = new Schema<>();
            nullSchema.setTypes(Set.of("null"));
            Schema<Object> category = new Schema<>();
            category.setAnyOf(List.of(new Schema<>().$ref("#/components/schemas/NamedReference"), nullSchema));
            for (String name : List.of("DatasetSummary", "DatasetDetail")) {
                Schema<?> dataset = document.getComponents().getSchemas().get(name);
                if (dataset != null && dataset.getProperties() != null) dataset.getProperties().put("category", category);
            }
            nullable(document, "MetadataDraft", "primaryCategoryId", "currentPublicationId", "reviewRequestId", "reviewDecisionId", "updatedAt");
            nullable(document, "ReviewRequest", "decisionId", "decision", "reason");
            nullable(document, "Publication", "withdrawnAt", "withdrawalReason");
            nullable(document, "TermCandidatePage", "basis");
            nullable(document, "TermFieldUsage", "primaryCategoryName", "latestPublication");
            nullable(document, "MetadataDetails", "department", "license", "updateFrequency", "spatialCoverage", "temporalStart", "temporalEnd");
            nullable(document, "AnalysisRun", "datasetRevisionId", "errorCode");
            nullable(document, "AuthBootstrapResponse", "csrfHeaderName", "csrfToken");
            nullable(document, "CatalogSearchPage", "feedback");
            nullable(document, "DatasetPage", "feedback");
            nullable(document, "HistoryDetail", "queryRepresentation", "rating", "ratedAt", "returnedPage", "returnedSize");
            optionalNullable(document, "CreateDataset", "regionIds", "hubIds", "topicIds", "details");
            optionalNullable(document, "EditDraft", "regionIds", "hubIds", "topicIds", "details");
            optionalNullable(document, "AnalyticsReportRequest", "organizationId", "analysisKind", "status", "query", "hasQuery", "zeroResults");
            optionalNullable(document, "AnalyticsReportFilters", "analysisKind", "status", "query", "hasQuery", "zeroResults");
            optionalNullable(document, "AnalyticsReport", "organizationId");
            separateInputSchemas(document);
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void separateInputSchemas(OpenAPI document) {
        var schemas = document.getComponents().getSchemas();
        // The records are shared at runtime, but response presence is not an input validation constraint.
        // Serialize and deserialize the complete schema to avoid mutating response properties through aliases.
        Schema details = io.swagger.v3.core.util.Json31.mapper().convertValue(schemas.get("MetadataDetails"), Schema.class);
        details.setRequired(null);
        details.setDescription("선택 메타데이터 입력. 각 항목은 생략 또는 null 허용; keywords 생략/null은 빈 배열로 정규화된다. "
                + "details 자체를 생략/null로 수정하면 기존 값을 보존하고, 객체를 전송하면 세부 정보를 교체한다.");
        schemas.put("MetadataDetailsInput", details);
        optionalNullable(document, "MetadataDetailsInput", "keywords");
        for (String name : List.of("CreateDataset", "EditDraft")) {
            Schema input = schemas.get(name);
            input.getProperties().put("details", new Schema<>().$ref("#/components/schemas/MetadataDetailsInput"));
            optionalNullable(document, name, "details");
        }
        Schema fields = io.swagger.v3.core.util.Json31.mapper().convertValue(schemas.get("FieldDefinition"), Schema.class);
        fields.setRequired(new java.util.ArrayList<>(List.of("fieldPath", "physicalName", "displayName", "description", "dataType")));
        fields.setDescription("필드 입력. nullable/publicVisible 생략 또는 null은 false, ordinal 생략 또는 null은 0으로 처리한다. "
                + "approvedTermId를 지정하면 승인된 기관 접근 가능 용어와 비어 있지 않은 mappingEvidence가 필요하다.");
        schemas.put("FieldDefinitionInput", fields);
        optionalNullable(document, "FieldDefinitionInput", "nullable", "ordinal", "publicVisible");
        Schema fieldArray = (Schema) schemas.get("FieldsInput").getProperties().get("fields");
        fieldArray.setItems(new Schema<>().$ref("#/components/schemas/FieldDefinitionInput"));
    }

    private static void optionalNullable(OpenAPI document, String name, String... properties) {
        Schema<?> schema = document.getComponents().getSchemas().get(name);
        if (schema == null) return;
        List<String> required = schema.getRequired() == null ? null : List.copyOf(schema.getRequired());
        nullable(document, name, properties);
        schema.setRequired(required == null ? null : new java.util.ArrayList<>(required));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void nullable(OpenAPI document, String name, String... properties) {
        Schema schema = document.getComponents().getSchemas().get(name);
        if (schema == null || schema.getProperties() == null) return;
        for (String property : properties) {
            Schema existing = (Schema) schema.getProperties().get(property);
            if (existing == null) continue;
            Schema<Object> nullSchema = new Schema<>();
            nullSchema.setTypes(Set.of("null"));
            Schema<Object> nullable = new Schema<>();
            nullable.setAnyOf(List.of(existing, nullSchema));
            schema.getProperties().put(property, nullable);
            if (schema.getRequired() == null || !schema.getRequired().contains(property)) schema.addRequiredItem(property);
        }
    }
}
