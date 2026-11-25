package com.iflytek.skillhub.auth.gitcode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Short-lived cache for credential to identity resolution.
 *
 * <p>GitCode rate limits API traffic (400 requests per minute, 4000 per hour), so the resolved
 * identity is cached per credential. Keys are SHA-256 digests of the credential: the raw token or
 * cookie never reaches Redis or the in-memory fallback, and neither is ever logged.
 */
@Component
public class GitCodeIdentityCache {

    private static final Logger log = LoggerFactory.getLogger(GitCodeIdentityCache.class);

    private static final String KEY_PREFIX = "skillhub:gitcode:identity:";

    private static final String NEGATIVE_MARKER = "!";

    private static final int MEMORY_LIMIT = 5000;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;
    private final GitCodeAuthProperties properties;
    private final Map<String, MemoryEntry> memory = new ConcurrentHashMap<>();

    public GitCodeIdentityCache(ObjectProvider<StringRedisTemplate> redisProvider,
                                ObjectMapper objectMapper,
                                GitCodeAuthProperties properties) {
        this.redisProvider = redisProvider;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Builds the cache key for a credential. The credential itself never appears in the key.
     */
    public static String key(String channel, String credential) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((channel + '|' + credential).getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    public Optional<GitCodeIdentity> lookup(String key) {
        String stored = read(key);
        if (stored == null || NEGATIVE_MARKER.equals(stored)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(stored, GitCodeIdentity.class));
        } catch (Exception unreadable) {
            log.debug("Discarding unreadable GitCode identity cache entry");
            return Optional.empty();
        }
    }

    public boolean isNegative(String key) {
        return NEGATIVE_MARKER.equals(read(key));
    }

    public void putIdentity(String key, GitCodeIdentity identity) {
        try {
            write(key, objectMapper.writeValueAsString(identity), properties.getCache().getPositiveTtl());
        } catch (Exception unreadable) {
            log.debug("Skipping GitCode identity cache write");
        }
    }

    public void putNegative(String key) {
        write(key, NEGATIVE_MARKER, properties.getCache().getNegativeTtl());
    }

    private String read(String key) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                String stored = redis.opsForValue().get(key);
                if (stored != null) {
                    return stored;
                }
            } catch (RuntimeException unavailable) {
                log.debug("GitCode identity cache read fell back to memory");
            }
        }
        MemoryEntry entry = memory.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            memory.remove(key);
            return null;
        }
        return entry.value();
    }

    private void write(String key, String value, Duration ttl) {
        Duration effectiveTtl = ttl == null ? Duration.ofSeconds(30) : ttl;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                redis.opsForValue().set(key, value, effectiveTtl);
                return;
            } catch (RuntimeException unavailable) {
                log.debug("GitCode identity cache write fell back to memory");
            }
        }
        if (memory.size() >= MEMORY_LIMIT) {
            purgeExpired();
        }
        memory.put(key, new MemoryEntry(value, Instant.now().plus(effectiveTtl)));
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        memory.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        if (memory.size() >= MEMORY_LIMIT) {
            memory.clear();
        }
    }

    private record MemoryEntry(String value, Instant expiresAt) {
    }
}
