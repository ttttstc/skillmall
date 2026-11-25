package com.iflytek.skillhub.auth.gitcode;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Resolves the authenticated GitCode account from an access token.
 *
 * <p>Both credential sources (an explicit bearer header and the {@code GITCODE_ACCESS_TOKEN}
 * cookie) end at the same place: {@code GET {api-base-url}/user} with
 * {@code Authorization: Bearer <token>}. Credential values are never logged.
 */
@Component
public class GitCodeIdentityClient {

    private static final Logger log = LoggerFactory.getLogger(GitCodeIdentityClient.class);

    private final GitCodeAuthProperties properties;
    private final RestClient restClient;

    public GitCodeIdentityClient(GitCodeAuthProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        int timeoutMillis = (int) Math.max(500L, timeoutOrDefault(properties).toMillis());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static Duration timeoutOrDefault(GitCodeAuthProperties properties) {
        Duration configured = properties.getTimeout();
        return configured == null ? Duration.ofSeconds(3) : configured;
    }

    /**
     * Resolves the account behind a GitCode access token (PAT, OAuth2 token, or the token carried
     * by the GitCode web session cookie).
     *
     * @return the account, or empty when GitCode rejects the credential
     * @throws GitCodeUnavailableException when GitCode cannot be reached or answers transiently
     */
    public Optional<GitCodeIdentity> resolveAccessToken(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            return Optional.empty();
        }
        Map<String, String> headers = new HashMap<>();
        headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        return resolve(properties.resolvedUserEndpoint(), headers);
    }

    private Optional<GitCodeIdentity> resolve(String endpoint, Map<String, String> headers) {
        if (!StringUtils.hasText(endpoint)) {
            return Optional.empty();
        }
        try {
            ResponseEntity<Map<String, Object>> response = restClient.get()
                    .uri(endpoint)
                    .headers(requestHeaders -> headers.forEach(requestHeaders::set))
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {});
            return Optional.ofNullable(toIdentity(response.getBody()));
        } catch (RestClientResponseException rejected) {
            int status = rejected.getStatusCode().value();
            if (status == 401 || status == 403 || status == 404) {
                // Rejected credentials are a normal outcome; never log the credential itself.
                log.debug("GitCode identity lookup rejected: status={}", status);
                return Optional.empty();
            }
            // 418 is how the Huawei WAF in front of the enterprise instance answers a blocked
            // request; treat it like any other unavailability instead of a bad credential.
            throw new GitCodeUnavailableException("GitCode identity lookup failed with status " + status);
        } catch (RestClientException failure) {
            throw new GitCodeUnavailableException("GitCode identity lookup failed", failure);
        }
    }

    private GitCodeIdentity toIdentity(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        String id = text(payload.get("id"));
        if (!StringUtils.hasText(id)) {
            // The account id is the only stable coordinate; a payload without it cannot be bound.
            log.warn("GitCode user payload has no id; treating the credential as invalid");
            return null;
        }
        String login = text(payload.get("login"));
        if (!StringUtils.hasText(login)) {
            login = id;
        }
        return new GitCodeIdentity(id, login, text(payload.get("name")), text(payload.get("avatar_url")),
                text(payload.get("email")));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
