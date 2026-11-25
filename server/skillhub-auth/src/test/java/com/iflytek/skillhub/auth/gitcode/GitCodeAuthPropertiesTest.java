package com.iflytek.skillhub.auth.gitcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GitCodeAuthPropertiesTest {

    @Test
    void derivesUserEndpointFromApiBaseUrlWhenNotConfigured() {
        GitCodeAuthProperties properties = new GitCodeAuthProperties();
        assertEquals("https://gitcode.net/api/v5/user", properties.resolvedUserEndpoint());

        properties.setApiBaseUrl("https://gitcode.example/api/v5/");
        assertEquals("https://gitcode.example/api/v5/user", properties.resolvedUserEndpoint());

        properties.setUserEndpoint("https://gateway.example/gitcode/user");
        assertEquals("https://gateway.example/gitcode/user", properties.resolvedUserEndpoint());
    }

    @Test
    void parsesAllowlistEntriesWithRolesLoginsAndAccountIds() {
        GitCodeAuthProperties properties = new GitCodeAuthProperties();
        properties.setAdminAllowlist(List.of(" Alice : super_admin,USER_ADMIN ", "bob", "64e5ed8f7e20aa73efcbc302"));

        assertTrue(properties.hasAdminAllowlist());
        assertTrue(properties.isAdminAllowlisted("alice", null));
        assertTrue(properties.isAdminAllowlisted("ALICE", "unused"));
        assertTrue(properties.isAdminAllowlisted(null, "64e5ed8f7e20aa73efcbc302"));
        assertFalse(properties.isAdminAllowlisted("carol", "aaaaaaaaaaaaaaaaaaaaaaaa"));

        assertEquals(Set.of("SUPER_ADMIN", "USER_ADMIN"), properties.allowlistedAdminRoles("alice", null));
        assertTrue(properties.allowlistedAdminRoles("bob", null).isEmpty());
    }

    @Test
    void emptyAllowlistAdmitsNobody() {
        GitCodeAuthProperties properties = new GitCodeAuthProperties();
        assertFalse(properties.hasAdminAllowlist());
        assertFalse(properties.isAdminAllowlisted("alice", "64e5ed8f7e20aa73efcbc302"));
        assertTrue(properties.allowlistedAdminRoles("alice", null).isEmpty());
    }
}
