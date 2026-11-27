package com.iflytek.skillhub.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI metadata configuration for generated API documentation.
 *
 * <p>The bearer scheme has to be declared here or Swagger UI renders no Authorize control at all
 * and the generated document carries no security requirement — which is why "Bearer does not work
 * in Swagger" was reported even though the server-side token filter was complete. The credential is
 * a SkillHub API token (opaque, hashed at rest), not a JWT; the description says so to stop people
 * pasting an ID token and concluding the API is broken.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI skillhubOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SkillHub API")
                        .description("Skills Registry Platform")
                        .version("0.1.0-beta.7"))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local development")
                ))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("SkillHub API token")
                                .description("""
                                        Create a token in Settings → API tokens. Use the token value only \
                                        (Swagger adds the "Bearer " prefix).

                                        Two limits worth knowing before reporting a bug:
                                        1. Only /api/v1/** operations that appear in the token policy \
                                        registry are reachable with a token; everything else answers 403 \
                                        "API token cannot access endpoint" by design.
                                        2. Write calls from a browser that still holds a session cookie are \
                                        rejected by CSRF regardless of the token. Use a private window or \
                                        the CLI for writes.
                                        """)))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
