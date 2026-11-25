package com.iflytek.skillhub.auth.gitcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class GitCodeIdentityCacheTest {

    private GitCodeIdentityCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        cache = new GitCodeIdentityCache(redisProvider, new ObjectMapper(), new GitCodeAuthProperties());
    }

    @Test
    void cacheKeyNeverContainsTheCredential() {
        String key = GitCodeIdentityCache.key("pat", "super-secret-token");

        assertTrue(key.startsWith("skillhub:gitcode:identity:"));
        assertFalse(key.contains("super-secret-token"));
        assertNotEquals(key, GitCodeIdentityCache.key("cookie", "super-secret-token"));
        assertNotEquals(key, GitCodeIdentityCache.key("pat", "another-secret"));
    }

    @Test
    void storesAndReadsResolvedIdentities() {
        String key = GitCodeIdentityCache.key("pat", "token-a");
        GitCodeIdentity identity = new GitCodeIdentity("64e5ed8f7e20aa73efcbc302", "gitcode-xxm", "xxm", null, null);

        assertTrue(cache.lookup(key).isEmpty());
        cache.putIdentity(key, identity);

        GitCodeIdentity cached = cache.lookup(key).orElseThrow();
        assertEquals(identity.id(), cached.id());
        assertEquals(identity.login(), cached.login());
        assertFalse(cache.isNegative(key));
    }

    @Test
    void negativeEntriesAreDistinguishableFromMisses() {
        String key = GitCodeIdentityCache.key("pat", "token-b");

        cache.putNegative(key);

        assertTrue(cache.isNegative(key));
        assertTrue(cache.lookup(key).isEmpty());
        assertFalse(cache.isNegative(GitCodeIdentityCache.key("pat", "token-c")));
    }
}
