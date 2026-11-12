package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitcodeUrlParserTest {

    private final GitcodeUrlParser parser = new GitcodeUrlParser();

    @Test
    void parsesOwnerAndRepository() {
        GitcodeUrlParser.ParsedRepositoryUrl parsed = parser.parse("https://gitcode.com/acme/skills");

        assertThat(parsed.owner()).isEqualTo("acme");
        assertThat(parsed.repository()).isEqualTo("skills");
        assertThat(parsed.hasRef()).isFalse();
        assertThat(parsed.path()).isEmpty();
    }

    @Test
    void stripsTrailingGitSuffixAndPathSlash() {
        GitcodeUrlParser.ParsedRepositoryUrl parsed = parser.parse("https://gitcode.com/acme/skills.git/");

        assertThat(parsed.repository()).isEqualTo("skills");
        assertThat(parsed.hasRef()).isFalse();
    }

    @Test
    void acceptsAddressWithoutScheme() {
        GitcodeUrlParser.ParsedRepositoryUrl parsed = parser.parse("gitcode.com/acme/skills");

        assertThat(parsed.owner()).isEqualTo("acme");
        assertThat(parsed.repository()).isEqualTo("skills");
    }

    @Test
    void parsesTreeRef() {
        GitcodeUrlParser.ParsedRepositoryUrl parsed =
                parser.parse("https://gitcode.com/acme/skills/tree/v1.2.0");

        assertThat(parsed.ref()).isEqualTo("v1.2.0");
        assertThat(parsed.path()).isEmpty();
    }

    @Test
    void parsesTreeRefAndSkillPath() {
        GitcodeUrlParser.ParsedRepositoryUrl parsed =
                parser.parse("https://gitcode.com/acme/skills/tree/main/skills/cann-build/");

        assertThat(parsed.ref()).isEqualTo("main");
        assertThat(parsed.path()).isEqualTo("skills/cann-build");
    }

    @Test
    void ignoresQueryStringBecauseItCannotChangeRepositoryCoordinates() {
        GitcodeUrlParser.ParsedRepositoryUrl parsed =
                parser.parse("https://gitcode.com/acme/skills/tree/main?tab=readme-ov-file");

        assertThat(parsed.owner()).isEqualTo("acme");
        assertThat(parsed.ref()).isEqualTo("main");
    }

    @Test
    void rejectsForeignHost() {
        assertInvalid("https://gitlab.com/acme/skills");
        assertInvalid("https://gitcode.com.evil.example/acme/skills");
        assertInvalid("https://api.gitcode.com/acme/skills");
    }

    @Test
    void rejectsIpLiteralsAndLoopback() {
        assertInvalid("https://192.168.1.10/acme/skills");
        assertInvalid("https://10.0.0.5/acme/skills");
        assertInvalid("https://127.0.0.1/acme/skills");
        assertInvalid("https://[::1]/acme/skills");
        assertInvalid("https://localhost/acme/skills");
    }

    @Test
    void rejectsCredentialBearingAddresses() {
        assertInvalid("https://user:secret@gitcode.com/acme/skills");
    }

    @Test
    void rejectsNonStandardPortAndScheme() {
        assertInvalid("https://gitcode.com:8443/acme/skills");
        assertInvalid("ftp://gitcode.com/acme/skills");
        assertInvalid("file://gitcode.com/acme/skills");
    }

    @Test
    void rejectsTraversingPaths() {
        assertInvalid("https://gitcode.com/acme/skills/tree/main/../../etc");
        assertInvalid("https://gitcode.com/../acme/skills");
    }

    @Test
    void rejectsMissingRepositoryOrUnsupportedTreeSegment() {
        assertInvalid("https://gitcode.com/acme");
        assertInvalid("https://gitcode.com/");
        assertInvalid("https://gitcode.com/acme/skills/tree");
        assertInvalid("https://gitcode.com/acme/skills/blob/main/SKILL.md");
    }

    @Test
    void rejectsBlankInput() {
        assertInvalid(null);
        assertInvalid("   ");
    }

    private void assertInvalid(String url) {
        assertThatThrownBy(() -> parser.parse(url))
                .isInstanceOf(DomainBadRequestException.class)
                .satisfies(exception -> assertThat(((DomainBadRequestException) exception).messageCode())
                        .isEqualTo(GitcodeErrors.URL_INVALID));
    }
}
