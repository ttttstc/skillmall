package com.iflytek.skillhub.gitcode;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class GitcodeSkillScannerTest {

    private static final String SKILL_MD = """
            ---
            name: demo-skill
            description: A demo skill
            version: 1.0.0
            ---
            # Demo
            """;

    private final GitcodeClient client = Mockito.mock(GitcodeClient.class);
    private final GitcodeSkillScanner scanner = new GitcodeSkillScanner(client, new SkillMetadataParser());
    private final Map<String, List<GitcodeClient.Entry>> directories = new HashMap<>();
    private final Map<String, byte[]> files = new HashMap<>();
    private final GitcodeClient.Budget budget =
            new GitcodeClient.Budget(Instant.parse("2026-11-12T00:05:00Z"));

    @BeforeEach
    void stubRepository() {
        // The scanner walks one flattened tree instead of listing directories, so the fixtures below
        // are projected into a single entry list.
        Mockito.when(client.tree(any(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> directories.values().stream().flatMap(List::stream).toList());
        Mockito.when(client.listDirectory(any(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> directories.getOrDefault(
                        invocation.getArgument(4, String.class), List.of()));
        Mockito.when(client.readFile(any(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(4, String.class);
                    byte[] content = files.get(path);
                    if (content == null) {
                        throw new DomainNotFoundException(GitcodeErrors.SKILL_MD_NOT_FOUND);
                    }
                    return new GitcodeClient.FileContent(path, content.length, content);
                });
    }

    @Test
    void findsSkillAtRepositoryRoot() {
        directory("", file("SKILL.md"), file("README.md"), directory("docs"));
        directory("docs", file("docs/usage.md"));
        content("SKILL.md", SKILL_MD);
        content("README.md", "# Repo");
        content("docs/usage.md", "usage");

        List<GitcodeSkillScanner.ScannedSkill> skills = scanner.scan(budget, "acme", "skills", "sha", "");

        assertThat(skills).hasSize(1);
        GitcodeSkillScanner.ScannedSkill skill = skills.get(0);
        assertThat(skill.path()).isEmpty();
        assertThat(skill.name()).isEqualTo("demo-skill");
        assertThat(skill.description()).isEqualTo("A demo skill");
        assertThat(skill.version()).isEqualTo("1.0.0");
        assertThat(skill.fileCount()).isEqualTo(3);
        assertThat(skill.totalBytes()).isEqualTo(byteLengthOf("SKILL.md", "README.md", "docs/usage.md"));
        assertThat(skill.entries()).extracting(PackageEntry::path)
                .containsExactly("SKILL.md", "README.md", "docs/usage.md");
        assertThat(skill.entries()).filteredOn(entry -> "SKILL.md".equals(entry.path()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.contentType()).isEqualTo("text/markdown"));
    }

    @Test
    void findsNestedSkillDirectory() {
        directory("", directory("skills"));
        directory("skills", directory("skills/cann-build"));
        directory("skills/cann-build", file("skills/cann-build/SKILL.md"));
        content("skills/cann-build/SKILL.md", SKILL_MD);

        List<GitcodeSkillScanner.ScannedSkill> skills = scanner.scan(budget, "acme", "skills", "sha", "");

        assertThat(skills).singleElement().satisfies(skill -> {
            assertThat(skill.path()).isEqualTo("skills/cann-build");
            assertThat(skill.entries()).extracting(PackageEntry::path).containsExactly("SKILL.md");
        });
    }

    @Test
    void reportsEverySkillDirectoryUnderTheScannedRoot() {
        directory("", directory("a"), directory("b"));
        directory("a", file("a/SKILL.md"));
        directory("b", file("b/SKILL.md"));
        content("a/SKILL.md", SKILL_MD);
        content("b/SKILL.md", SKILL_MD);

        List<GitcodeSkillScanner.ScannedSkill> skills = scanner.scan(budget, "acme", "skills", "sha", "");

        assertThat(skills).extracting(GitcodeSkillScanner.ScannedSkill::path).containsExactly("a", "b");
    }

    @Test
    void keepsFrontmatterWithoutVersionEmpty() {
        directory("", file("SKILL.md"));
        content("SKILL.md", """
                ---
                name: demo-skill
                description: A demo skill
                ---
                # Demo
                """);

        List<GitcodeSkillScanner.ScannedSkill> skills = scanner.scan(budget, "acme", "skills", "sha", "");

        assertThat(skills).singleElement().satisfies(skill -> assertThat(skill.version()).isNull());
    }

    @Test
    void skipsEntriesThatAreNeitherFilesNorDirectories() {
        directory("", new GitcodeClient.Entry("symlink", "link", "link"), file("SKILL.md"));
        content("SKILL.md", SKILL_MD);

        List<GitcodeSkillScanner.ScannedSkill> skills = scanner.scan(budget, "acme", "skills", "sha", "");

        assertThat(skills).singleElement()
                .satisfies(skill -> assertThat(skill.entries()).extracting(PackageEntry::path)
                        .containsExactly("SKILL.md"));
    }

    @Test
    void rejectsRepositoryWithoutSkillMd() {
        directory("", file("README.md"));
        content("README.md", "# Repo");

        assertThatThrownBy(() -> scanner.scan(budget, "acme", "skills", "sha", ""))
                .isInstanceOf(DomainNotFoundException.class)
                .satisfies(exception -> assertThat(((DomainNotFoundException) exception).messageCode())
                        .isEqualTo(GitcodeErrors.SKILL_MD_NOT_FOUND));
    }

    @Test
    void rejectsSkillDirectoriesAboveTheFileCountLimit() {
        List<GitcodeClient.Entry> entries = new ArrayList<>();
        entries.add(file("SKILL.md"));
        for (int index = 0; index <= GitcodeSkillScanner.MAX_FILES; index++) {
            entries.add(file("file-" + index + ".md"));
        }
        directory("", entries.toArray(new GitcodeClient.Entry[0]));
        content("SKILL.md", SKILL_MD);

        GitcodeSkillScanner.Tree tree = scanner.walk(budget, "acme", "skills", "sha", "");

        // The limit belongs to the chosen package, not to the repository: discovery succeeds and the
        // per-skill bound is enforced when that directory is loaded.
        assertThatThrownBy(() -> scanner.load(budget, "acme", "skills", "sha", tree, ""))
                .isInstanceOf(DomainBadRequestException.class)
                .satisfies(exception -> assertThat(((DomainBadRequestException) exception).messageCode())
                        .isEqualTo(GitcodeErrors.TOO_MANY_FILES));
    }

    @Test
    void rejectsRepositoriesAboveTheTreeEntryLimit() {
        List<GitcodeClient.Entry> entries = new ArrayList<>();
        for (int index = 0; index <= GitcodeSkillScanner.MAX_TREE_ENTRIES; index++) {
            entries.add(file("file-" + index + ".md"));
        }
        directory("", entries.toArray(new GitcodeClient.Entry[0]));

        assertThatThrownBy(() -> scanner.walk(budget, "acme", "skills", "sha", ""))
                .isInstanceOf(DomainBadRequestException.class)
                .satisfies(exception -> assertThat(((DomainBadRequestException) exception).messageCode())
                        .isEqualTo(GitcodeErrors.TOO_MANY_FILES));
    }

    @Test
    void rejectsSingleFileAboveThePerFileLimit() {
        directory("", file("SKILL.md"), file("assets/large.bin"));
        content("SKILL.md", SKILL_MD);
        content("assets/large.bin", new byte[(int) GitcodeClient.MAX_SINGLE_FILE_BYTES + 1]);

        assertThatThrownBy(() -> scanner.scan(budget, "acme", "skills", "sha", ""))
                .isInstanceOf(DomainBadRequestException.class)
                .satisfies(exception -> assertThat(((DomainBadRequestException) exception).messageCode())
                        .isEqualTo(GitcodeErrors.TOO_LARGE));
    }

    @Test
    void rejectsPackageAboveTheTotalSizeLimit() {
        int chunk = (int) (GitcodeClient.MAX_SINGLE_FILE_BYTES - 1024);
        List<GitcodeClient.Entry> entries = new ArrayList<>();
        entries.add(file("SKILL.md"));
        for (int index = 0; index < 6; index++) {
            entries.add(file("chunk-" + index + ".bin"));
        }
        directory("", entries.toArray(new GitcodeClient.Entry[0]));
        content("SKILL.md", SKILL_MD);
        for (int index = 0; index < 6; index++) {
            content("chunk-" + index + ".bin", new byte[chunk]);
        }

        assertThatThrownBy(() -> scanner.scan(budget, "acme", "skills", "sha", ""))
                .isInstanceOf(DomainBadRequestException.class)
                .satisfies(exception -> assertThat(((DomainBadRequestException) exception).messageCode())
                        .isEqualTo(GitcodeErrors.TOO_LARGE));
    }

    @Test
    void stopsDescendingAtTheDepthLimit() {
        String parent = "";
        for (int depth = 1; depth <= GitcodeSkillScanner.MAX_DEPTH + 1; depth++) {
            String child = depth == 1 ? "d1" : parent + "/d" + depth;
            directory(parent, directory(child));
            parent = child;
        }
        // parent now sits at depth MAX_DEPTH + 1, one level below the traversal limit.
        directory(parent, file(parent + "/SKILL.md"));
        content(parent + "/SKILL.md", SKILL_MD);

        assertThatThrownBy(() -> scanner.scan(budget, "acme", "skills", "sha", ""))
                .isInstanceOf(DomainNotFoundException.class);
        Mockito.verify(client, Mockito.never()).listDirectory(any(), anyString(), anyString(), anyString(),
                Mockito.eq(parent));
    }

    @Test
    void rejectsTraversingPaths() {
        assertThatThrownBy(() -> scanner.walk(budget, "acme", "skills", "sha", "../etc"))
                .isInstanceOf(DomainBadRequestException.class)
                .satisfies(exception -> assertThat(((DomainBadRequestException) exception).messageCode())
                        .isEqualTo(GitcodeErrors.URL_INVALID));
    }

    private long byteLengthOf(String... paths) {
        long total = 0;
        for (String path : paths) {
            total += files.get(path).length;
        }
        return total;
    }

    private static GitcodeClient.Entry directory(String path) {
        return new GitcodeClient.Entry("dir", path.substring(path.lastIndexOf('/') + 1), path);
    }

    private static GitcodeClient.Entry file(String path) {
        return new GitcodeClient.Entry("file", path.substring(path.lastIndexOf('/') + 1), path);
    }

    private void directory(String path, GitcodeClient.Entry... entries) {
        directories.put(path, List.of(entries));
    }

    private void content(String path, String text) {
        files.put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private void content(String path, byte[] bytes) {
        files.put(path, bytes);
    }
}
