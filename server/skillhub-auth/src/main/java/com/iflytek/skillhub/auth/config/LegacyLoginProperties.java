package com.iflytek.skillhub.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Switches the pre-GitCode login surfaces on or off.
 *
 * <p>When disabled, local password login, SkillHub-issued API tokens and the CLI device flow are
 * removed from the application, leaving the GitCode channels as the only way to authenticate. The
 * default keeps the open-source behaviour unchanged.
 */
@Configuration
@ConfigurationProperties(prefix = "skillhub.auth.legacy-login")
public class LegacyLoginProperties {

    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
