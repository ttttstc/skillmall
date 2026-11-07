package com.iflytek.skillhub.gitcode;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Bounds for importing a pinned GitCode snapshot. */
@Component
@ConfigurationProperties(prefix = "skillhub.gitcode-import")
public class GitCodeImportProperties {

    /** GitCode v5 API root. Only this provider is supported by the importer. */
    private String apiBaseUrl = "https://gitcode.com/api/v5";

    private Duration requestTimeout = Duration.ofSeconds(20);

    /** Maximum number of directories visited while discovering skill roots. */
    private int maxDirectories = 200;

    /** Maximum directory nesting descended while discovering skill roots. */
    private int maxDepth = 8;

    /** Maximum length of a repository-relative skill path. */
    private int maxPathLength = 512;

    /** Retries after an anonymous rate limit response, per provider request. */
    private int maxRateLimitRetries = 2;

    /** Base delay for the first rate-limit retry; later retries wait longer. */
    private Duration rateLimitBackoff = Duration.ofMillis(800);

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaxDirectories() {
        return maxDirectories;
    }

    public void setMaxDirectories(int maxDirectories) {
        this.maxDirectories = maxDirectories;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxPathLength() {
        return maxPathLength;
    }

    public void setMaxPathLength(int maxPathLength) {
        this.maxPathLength = maxPathLength;
    }

    public int getMaxRateLimitRetries() {
        return maxRateLimitRetries;
    }

    public void setMaxRateLimitRetries(int maxRateLimitRetries) {
        this.maxRateLimitRetries = maxRateLimitRetries;
    }

    public Duration getRateLimitBackoff() {
        return rateLimitBackoff;
    }

    public void setRateLimitBackoff(Duration rateLimitBackoff) {
        this.rateLimitBackoff = rateLimitBackoff;
    }
}
