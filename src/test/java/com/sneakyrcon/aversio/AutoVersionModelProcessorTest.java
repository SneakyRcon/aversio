package com.sneakyrcon.aversio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.maven.building.FileSource;
import org.apache.maven.model.building.ModelProcessor;
import org.junit.jupiter.api.Test;

class AutoVersionModelProcessorTest {

    @Test
    void inputPomDeterminesRepositoryWhenWorkingDirectoryIsOutsideCheckout() throws Exception {
        Path checkout = createCheckout();
        Path pom = checkout.resolve("module/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, "<project/>\n");
        Path outside = Files.createTempDirectory("version-helper-outside-");

        String previousUserDirectory = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", outside.toString());
            assertEquals(checkout.toFile().getAbsoluteFile(),
                    AutoVersionModelProcessor.repositoryRoot(pom.toFile(), Map.of()));
        } finally {
            restoreProperty("user.dir", previousUserDirectory);
        }
    }

    @Test
    void mavenSourceOptionDeterminesRepositoryForStreamReads() throws Exception {
        Path checkout = createCheckout();
        Path pom = checkout.resolve("pom.xml");
        Files.writeString(pom, "<project/>\n");

        Map<String, Object> options = Map.of(ModelProcessor.SOURCE, new FileSource(pom.toFile()));

        assertEquals(checkout.toFile().getAbsoluteFile(),
                AutoVersionModelProcessor.repositoryRoot(null, options));
    }

    @Test
    void mavenExecutionRootIsUsedBeforeWorkingDirectoryFallback() throws Exception {
        Path checkout = createCheckout();
        Path outside = Files.createTempDirectory("version-helper-outside-");
        String previousUserDirectory = System.getProperty("user.dir");
        String previousExecutionRoot = System.getProperty("maven.multiModuleProjectDirectory");
        try {
            System.setProperty("user.dir", outside.toString());
            System.setProperty("maven.multiModuleProjectDirectory", checkout.toString());
            assertEquals(checkout.toFile().getAbsoluteFile(),
                    AutoVersionModelProcessor.repositoryRoot(null, Map.of()));
        } finally {
            restoreProperty("user.dir", previousUserDirectory);
            restoreProperty("maven.multiModuleProjectDirectory", previousExecutionRoot);
        }
    }

    private static Path createCheckout() throws Exception {
        Path checkout = Files.createTempDirectory("version-helper-checkout-");
        Files.createDirectory(checkout.resolve(".git"));
        return checkout;
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
