package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitCodeRepoUrlTest {

    @Test
    void acceptsPublicGitCodeRepositoryUrl() {
        GitCodeRepoUrl url = GitCodeRepoUrl.parse("https://gitcode.com/openharmony/docs");

        assertThat(url.owner()).isEqualTo("openharmony");
        assertThat(url.repo()).isEqualTo("docs");
        assertThat(url.canonicalUrl()).isEqualTo("https://gitcode.com/openharmony/docs");
    }

    @Test
    void acceptsARepositoryInsideANestedGitCodeGroup() {
        // GitCode mirrors and organisational groups nest one level, e.g. GitHub_Trending/skills13.
        GitCodeRepoUrl url = GitCodeRepoUrl.parse("https://gitcode.com/GitHub_Trending/skills13/skills");

        assertThat(url.owner()).isEqualTo("GitHub_Trending/skills13");
        assertThat(url.repo()).isEqualTo("skills");
        assertThat(url.canonicalUrl()).isEqualTo("https://gitcode.com/GitHub_Trending/skills13/skills");
    }

    @Test
    void rejectsBrowserViewUrlsAndDeeperPaths() {
        for (String source : new String[]{
                "https://gitcode.com/openharmony/docs/tree/master",
                "https://gitcode.com/openharmony/docs/blob/master/README.md",
                "https://gitcode.com/a/b/c/d"}) {
            assertThatThrownBy(() -> GitCodeRepoUrl.parse(source))
                    .as(source)
                    .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                            assertThat(exception.messageCode()).isEqualTo("error.skill.import.url.invalid"));
        }
    }

    @Test
    void normalizesGitSuffixAndTrailingSlash() {
        assertThat(GitCodeRepoUrl.parse("https://gitcode.com/openharmony/docs.git").repo())
                .isEqualTo("docs");
        assertThat(GitCodeRepoUrl.parse("https://gitcode.com/openharmony/docs/").repo())
                .isEqualTo("docs");
    }

    @Test
    void rejectsOtherHostsAsUnsupportedSource() {
        for (String source : new String[]{
                "https://github.com/openharmony/docs",
                "https://gitlab.com/openharmony/docs",
                "https://gitee.com/openharmony/docs",
                "https://example.com/openharmony/docs"}) {
            assertThatThrownBy(() -> GitCodeRepoUrl.parse(source))
                    .as(source)
                    .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                            assertThat(exception.messageCode()).isEqualTo("error.skill.import.source.unsupported"));
        }
    }

    @Test
    void rejectsNonHttpsSchemeAsUnsupportedSource() {
        assertThatThrownBy(() -> GitCodeRepoUrl.parse("http://gitcode.com/openharmony/docs"))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.source.unsupported"));
        assertThatThrownBy(() -> GitCodeRepoUrl.parse("git@gitcode.com:openharmony/docs.git"))
                .isInstanceOfSatisfying(DomainBadRequestException.class, exception ->
                        assertThat(exception.messageCode()).isEqualTo("error.skill.import.source.unsupported"));
    }

    @Test
    void rejectsUrlsThatAreNotARepositoryRoot() {
        for (String source : new String[]{
                "",
                "   ",
                "https://gitcode.com/openharmony",
                "https://gitcode.com/openharmony/docs/tree/master",
                "https://gitcode.com/openharmony/docs?ref=master",
                "https://gitcode.com/openharmony/docs#readme",
                "https://user:pass@gitcode.com/openharmony/docs",
                "https://gitcode.com:443/openharmony/docs"}) {
            assertThatThrownBy(() -> GitCodeRepoUrl.parse(source))
                    .as(source)
                    .isInstanceOf(DomainBadRequestException.class);
        }
    }

    @Test
    void rejectsTraversalInSegments() {
        assertThatThrownBy(() -> GitCodeRepoUrl.parse("https://gitcode.com/../docs"))
                .isInstanceOf(DomainBadRequestException.class);
        assertThatThrownBy(() -> GitCodeRepoUrl.parse("https://gitcode.com/owner/.."))
                .isInstanceOf(DomainBadRequestException.class);
    }
}
