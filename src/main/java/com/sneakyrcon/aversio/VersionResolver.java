package com.sneakyrcon.aversio;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VersionResolver {

    static final String BOOTSTRAP_VERSION = "0.0.0-snapshot";

    private static final Pattern STABLE_TAG = Pattern.compile(
            "^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    private static final Pattern COMMIT_HEADER = Pattern.compile(
            "^([a-z][a-z0-9-]*)(?:\\(([^()\\r\\n]+)\\))?(!)?: .+$");
    private static final Pattern BREAKING_FOOTER = Pattern.compile(
            "^BREAKING(?: CHANGE|-CHANGE):\\s*.+$");

    private VersionResolver() {
    }

    static String resolve(File repositoryRoot) throws IOException {
        String explicit = firstNonBlank(System.getProperty("versioning.version"),
                System.getenv("VERSIONING_VERSION"));
        if (!explicit.isEmpty()) {
            validateVersion(explicit);
            return explicit;
        }
        if (repositoryRoot == null || run(repositoryRoot, "rev-parse", "--git-dir").exitCode != 0) {
            return BOOTSTRAP_VERSION;
        }

        String commit = git(repositoryRoot, "rev-parse", "HEAD");
        String shortCommit = git(repositoryRoot, "rev-parse", "--short=7", "HEAD");
        boolean dirty = !git(repositoryRoot, "status", "--porcelain=v1", "--untracked-files=all").isEmpty();
        TagInfo nearestTag = nearestStableTag(repositoryRoot, commit);
        String baseVersion = nearestTag == null ? "0.0.0" : nearestTag.version.toString();
        String range = nearestTag == null ? commit : nearestTag.commit + ".." + commit;
        Bump bump = highestBump(repositoryRoot, range);

        if (nearestTag != null && nearestTag.commit.equals(commit) && !dirty) {
            return baseVersion;
        }

        Version next = Version.parse(baseVersion).bump(bump);
        return next + "-" + shortCommit + (dirty ? "-dirty" : "") + "-snapshot";
    }

    private static TagInfo nearestStableTag(File root, String commit) throws IOException {
        List<TagInfo> candidates = new ArrayList<>();
        String tags = git(root, "for-each-ref", "--format=%(refname:short)", "refs/tags");
        if (tags.isEmpty()) {
            return null;
        }
        for (String tag : tags.split("\\R")) {
            Matcher matcher = STABLE_TAG.matcher(tag.trim());
            if (!matcher.matches()) {
                continue;
            }
            String tagCommit = gitOrEmpty(root, "rev-parse", tag + "^{commit}");
            if (tagCommit.isEmpty() || !isAncestor(root, tagCommit, commit)) {
                continue;
            }
            long distance = count(root, tagCommit + ".." + commit);
            candidates.add(new TagInfo(tagCommit,
                    Version.parse(matcher.group(1), matcher.group(2), matcher.group(3)), distance));
        }
        return candidates.stream()
                .min(Comparator.comparingLong((TagInfo tag) -> tag.distance)
                        .thenComparing((TagInfo tag) -> tag.version, Comparator.reverseOrder()))
                .orElse(null);
    }

    private static boolean isAncestor(File root, String ancestor, String descendant) throws IOException {
        return run(root, "merge-base", "--is-ancestor", ancestor, descendant).exitCode == 0;
    }

    private static Bump highestBump(File root, String range) throws IOException {
        String log = git(root, "log", "--reverse", "--format=%H%x1f%B%x1e", range);
        Bump highest = Bump.NONE;
        for (String record : log.split("\\u001e")) {
            if (record.trim().isEmpty()) {
                continue;
            }
            int separator = record.indexOf('\u001f');
            if (separator < 0) {
                throw new IOException("Could not read a Git commit while resolving the version");
            }
            String commit = record.substring(0, separator).trim();
            String message = record.substring(separator + 1);
            Bump bump = bumpForCommit(commit, message);
            if (bump.rank > highest.rank) {
                highest = bump;
            }
        }
        return highest;
    }

    private static Bump bumpForCommit(String commit, String message) throws IOException {
        String[] lines = message.split("\\R", -1);
        String subject = lines.length == 0 ? "" : lines[0].trim();
        Matcher header = COMMIT_HEADER.matcher(subject);
        if (!header.matches()) {
            throw new IOException("Commit " + commit
                    + " does not use a valid Conventional Commit header: " + subject);
        }
        if ("!".equals(header.group(3)) || Arrays.stream(lines)
                .skip(1)
                .map(String::trim)
                .anyMatch(line -> BREAKING_FOOTER.matcher(line).matches())) {
            return Bump.MAJOR;
        }
        String type = header.group(1);
        if ("feat".equals(type)) {
            return Bump.MINOR;
        }
        if ("fix".equals(type) || "perf".equals(type) || "revert".equals(type)) {
            return Bump.PATCH;
        }
        return Bump.NONE;
    }

    private static long count(File root, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("rev-list");
        command.add("--count");
        command.addAll(Arrays.asList(args));
        return Long.parseLong(git(root, command.toArray(new String[0])));
    }

    private static String gitOrEmpty(File root, String... args) throws IOException {
        Result result = run(root, args);
        return result.exitCode == 0 ? result.output : "";
    }

    private static String git(File root, String... args) throws IOException {
        Result result = run(root, args);
        if (result.exitCode != 0) {
            throw new IOException("Git command failed: " + String.join(" ", args) + "\n" + result.output);
        }
        return result.output;
    }

    private static Result run(File root, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.getAbsolutePath());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append('\n');
                }
                output.append(line);
            }
        }
        try {
            int exitCode = process.waitFor();
            return new Result(exitCode, output.toString().trim());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running Git", exception);
        }
    }

    private static void validateVersion(String version) {
        Matcher matcher = SEMVER.matcher(version);
        if (!matcher.matches() || hasNumericPrereleaseLeadingZero(matcher.group(4))) {
            throw new IllegalArgumentException("Configured version is not SemVer-compatible: " + version);
        }
    }

    private static boolean hasNumericPrereleaseLeadingZero(String prerelease) {
        if (prerelease == null) {
            return false;
        }
        for (String identifier : prerelease.split("\\.")) {
            if (identifier.length() > 1 && identifier.matches("0[0-9]+")) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private enum Bump {
        NONE(0),
        PATCH(1),
        MINOR(2),
        MAJOR(3);

        private final int rank;

        Bump(int rank) {
            this.rank = rank;
        }
    }

    private static final class TagInfo {
        private final String commit;
        private final Version version;
        private final long distance;

        private TagInfo(String commit, Version version, long distance) {
            this.commit = commit;
            this.version = version;
            this.distance = distance;
        }
    }

    private static final class Version implements Comparable<Version> {
        private final BigInteger major;
        private final BigInteger minor;
        private final BigInteger patch;

        private Version(BigInteger major, BigInteger minor, BigInteger patch) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
        }

        private static Version parse(String major, String minor, String patch) {
            return new Version(new BigInteger(major), new BigInteger(minor), new BigInteger(patch));
        }

        private static Version parse(String version) {
            Matcher matcher = SEMVER.matcher(version);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Not a valid SemVer value: " + version);
            }
            return parse(matcher.group(1), matcher.group(2), matcher.group(3));
        }

        private Version bump(Bump bump) {
            switch (bump) {
                case MAJOR:
                    return new Version(major.add(BigInteger.ONE), BigInteger.ZERO, BigInteger.ZERO);
                case MINOR:
                    return new Version(major, minor.add(BigInteger.ONE), BigInteger.ZERO);
                case PATCH:
                    return new Version(major, minor, patch.add(BigInteger.ONE));
                default:
                    return this;
            }
        }

        @Override
        public int compareTo(Version other) {
            int result = major.compareTo(other.major);
            if (result == 0) {
                result = minor.compareTo(other.minor);
            }
            if (result == 0) {
                result = patch.compareTo(other.patch);
            }
            return result;
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    private static final class Result {
        private final int exitCode;
        private final String output;

        private Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
