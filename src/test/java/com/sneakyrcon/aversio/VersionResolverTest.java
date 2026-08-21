package com.sneakyrcon.aversio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VersionResolverTest {

    @Test
    void cleanStableTagResolvesToTheExactReleaseVersion() throws Exception {
        Path repository = repository("chore: initialize repository");
        tag(repository, "v3.0.0");

        assertEquals("3.0.0", VersionResolver.resolve(repository.toFile()));
    }

    @Test
    void annotatedStableTagResolvesToTheExactReleaseVersion() throws Exception {
        Path repository = repository("chore: initialize repository");
        run(repository, "tag", "-a", "v3.0.0", "-m", "Aversio 3.0.0");

        assertEquals("3.0.0", VersionResolver.resolve(repository.toFile()));
    }

    @Test
    void fixCommitCreatesPatchSnapshot() throws Exception {
        Path repository = repository("chore: initialize repository");
        tag(repository, "v3.0.0");
        commit(repository, "fix: correct a version calculation");

        assertSnapshot(VersionResolver.resolve(repository.toFile()), "3.0.1", repository);
    }

    @Test
    void conventionalCommitScopesAcceptStandardFreeFormText() throws Exception {
        Path repository = repository("chore: initialize repository");
        tag(repository, "v3.0.0");
        commit(repository, "fix(api/v2): correct a version calculation");
        commit(repository, "feat(UI): expose the result");

        assertSnapshot(VersionResolver.resolve(repository.toFile()), "3.1.0", repository);
    }

    @Test
    void featureCommitCreatesMinorSnapshot() throws Exception {
        Path repository = repository("chore: initialize repository");
        tag(repository, "v3.0.0");
        commit(repository, "feat: calculate versions from commits");

        assertSnapshot(VersionResolver.resolve(repository.toFile()), "3.1.0", repository);
    }

    @Test
    void breakingHeaderCreatesMajorSnapshot() throws Exception {
        Path repository = repository("chore: initialize repository");
        tag(repository, "v3.0.0");
        commit(repository, "feat!: change the version contract");

        assertSnapshot(VersionResolver.resolve(repository.toFile()), "4.0.0", repository);
    }

    @Test
    void breakingFooterCreatesMajorSnapshot() throws Exception {
        Path repository = repository("chore: initialize repository");
        tag(repository, "v3.0.0");
        commit(repository, "refactor: replace the resolver\n\nBREAKING CHANGE: the old output is removed");

        assertSnapshot(VersionResolver.resolve(repository.toFile()), "4.0.0", repository);
    }

    @Test
    void highestImpactWinsAcrossTheCommitRange() throws Exception {
        Path repository = repository("chore: initialize repository");
        tag(repository, "v1.0.0");
        commit(repository, "fix: patch one behavior");
        commit(repository, "feat: add one capability");
        commit(repository, "docs: explain the breaking migration\n\nBREAKING-CHANGE: callers must migrate");

        assertSnapshot(VersionResolver.resolve(repository.toFile()), "2.0.0", repository);
    }

    @Test
    void neutralCommitKeepsTheNearestReleaseNumbersButIsStillASnapshot() throws Exception {
        Path repository = repository("chore: initialize repository");
        tag(repository, "v3.0.0");
        commit(repository, "docs: improve the release documentation");

        assertSnapshot(VersionResolver.resolve(repository.toFile()), "3.0.0", repository);
    }

    @Test
    void dirtyTrackedWorktreeUsesDirtySnapshotSuffix() throws Exception {
        Path repository = repository("chore: initialize repository");
        tag(repository, "v3.0.0");
        Files.writeString(repository.resolve("file.txt"), "modified\n");

        String version = VersionResolver.resolve(repository.toFile());

        assertTrue(version.matches("3\\.0\\.0-[0-9a-f]{7}-dirty-snapshot"), version);
    }

    @Test
    void untrackedFileUsesDirtySnapshotSuffix() throws Exception {
        Path repository = repository("chore: initialize repository");
        tag(repository, "v3.0.0");
        Files.writeString(repository.resolve("untracked.txt"), "new\n");

        String version = VersionResolver.resolve(repository.toFile());

        assertTrue(version.matches("3\\.0\\.0-[0-9a-f]{7}-dirty-snapshot"), version);
    }

    @Test
    void nearestStableTagIsUsedInsteadOfAnOlderTag() throws Exception {
        Path repository = repository("chore: initialize repository");
        tag(repository, "v1.0.0");
        commit(repository, "fix: make the first release correction");
        tag(repository, "v1.0.1");
        commit(repository, "docs: document the second release");

        assertSnapshot(VersionResolver.resolve(repository.toFile()), "1.0.1", repository);
    }

    @Test
    void legacyAndPrereleaseTagsAreIgnored() throws Exception {
        Path repository = repository("chore: initialize repository");
        tag(repository, "2026.1");
        tag(repository, "besign-3.0.0");
        tag(repository, "v3.0.0-rc.1");
        commit(repository, "feat: start the SemVer line");

        assertSnapshot(VersionResolver.resolve(repository.toFile()), "0.1.0", repository);
    }

    @Test
    void noStableTagUsesZeroVersionAsTheBumpBase() throws Exception {
        Path repository = repository("fix: bootstrap a repository");

        assertSnapshot(VersionResolver.resolve(repository.toFile()), "0.0.1", repository);
    }

    @Test
    void noStableTagCanMakeTheFirstBreakingReleaseMajor() throws Exception {
        Path repository = repository("feat!: establish the first public contract");

        assertSnapshot(VersionResolver.resolve(repository.toFile()), "1.0.0", repository);
    }

    @Test
    void sourceTreeWithoutGitUsesSnapshotBootstrapVersion() throws Exception {
        Path sourceTree = Files.createTempDirectory("version-helper-no-git-");

        assertEquals("0.0.0-snapshot", VersionResolver.resolve(sourceTree.toFile()));
    }

    @Test
    void malformedCommitStopsVersionResolution() throws Exception {
        Path repository = repository("Initial commit without a type");

        IOException exception = assertThrows(IOException.class,
                () -> VersionResolver.resolve(repository.toFile()));

        assertTrue(exception.getMessage().contains("does not use a valid Conventional Commit header"),
                exception.getMessage());
    }

    @Test
    void explicitSemVerOverrideWinsAndInvalidValuesAreRejected() throws Exception {
        Path repository = Files.createTempDirectory("version-helper-override-");
        String previous = System.getProperty("versioning.version");
        try {
            System.setProperty("versioning.version", "1.0.0-rc.1");
            assertEquals("1.0.0-rc.1", VersionResolver.resolve(repository.toFile()));

            System.setProperty("versioning.version", "not-a-version");
            assertThrows(IllegalArgumentException.class, () -> VersionResolver.resolve(repository.toFile()));

            System.setProperty("versioning.version", "1.0.0-01");
            assertThrows(IllegalArgumentException.class, () -> VersionResolver.resolve(repository.toFile()));

            System.setProperty("versioning.version", "1.0.0-rc.01");
            assertThrows(IllegalArgumentException.class, () -> VersionResolver.resolve(repository.toFile()));
        } finally {
            restoreProperty("versioning.version", previous);
        }
    }

    private static Path repository(String initialMessage) throws Exception {
        Path repository = Files.createTempDirectory("version-helper-");
        run(repository, "init", "-b", "main");
        run(repository, "config", "user.email", "test@example.org");
        run(repository, "config", "user.name", "Version Test");
        Files.writeString(repository.resolve("file.txt"), "initial\n");
        run(repository, "add", "file.txt");
        run(repository, commitDate("2025-01-01T12:00:00Z"), "commit", "-m", initialMessage);
        return repository;
    }

    private static void commit(Path repository, String message) throws IOException, InterruptedException {
        run(repository, commitDate("2025-01-02T12:00:00Z"), "commit", "--allow-empty", "-m", message);
    }

    private static void tag(Path repository, String tag) throws IOException, InterruptedException {
        run(repository, "tag", tag);
    }

    private static void assertSnapshot(String version, String expectedBase, Path repository) throws Exception {
        String shortCommit = output(repository, "rev-parse", "--short=7", "HEAD");
        assertEquals(expectedBase + "-" + shortCommit + "-snapshot", version);
    }

    private static Map<String, String> commitDate(String date) {
        return Map.of("GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static void run(Path repository, String... arguments) throws IOException, InterruptedException {
        run(repository, Map.of(), arguments);
    }

    private static void run(Path repository, Map<String, String> environment, String... arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(List.of(arguments));
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        processBuilder.environment().putAll(environment);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }

    private static String output(Path repository, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        return output.trim();
    }
}
