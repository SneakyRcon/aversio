package com.sneakyrcon.aversio;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.building.FileSource;
import org.apache.maven.building.Source;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.building.ModelProcessor;

/**
 * Maven core extension which replaces the CI-friendly {@code revision}
 * property with a deterministic version calculated from the current Git
 * checkout. It intentionally changes only project and parent versions.
 */
@Named("core-default")
@Singleton
public final class AutoVersionModelProcessor implements ModelProcessor {

    private ModelProcessor delegatedModelProcessor;
    private String calculatedVersion;
    private File calculatedRepositoryRoot;
    private boolean initialized;

    /** Creates the Maven core model processor. */
    public AutoVersionModelProcessor() {
    }

    @Inject
    void setDelegatedModelProcessor(List<ModelProcessor> modelProcessors) {
        this.delegatedModelProcessor = modelProcessors.stream()
                .filter(processor -> processor != this)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Maven's default ModelProcessor was not found"));
    }

    @Override
    public File locatePom(File basedir) {
        return delegatedModelProcessor.locatePom(basedir);
    }

    @Override
    public Model read(File input, Map<String, ?> options) throws IOException {
        return applyVersion(delegatedModelProcessor.read(input, options), input, options);
    }

    @Override
    public Model read(Reader input, Map<String, ?> options) throws IOException {
        return applyVersion(delegatedModelProcessor.read(input, options), null, options);
    }

    @Override
    public Model read(InputStream input, Map<String, ?> options) throws IOException {
        return applyVersion(delegatedModelProcessor.read(input, options), null, options);
    }

    /**
     * Kept as a public hook for Maven versions which expose model processing
     * separately from ModelReader. Maven 3 invokes the reader methods above.
     *
     * @param model model to process
     * @param options model processing options
     * @return the processed model
     * @throws IOException if the Git version cannot be resolved
     */
    public Model processModel(Model model, Map<String, ?> options) throws IOException {
        return applyVersion(model, null, options);
    }

    private Model applyVersion(Model model, File input, Map<String, ?> options) throws IOException {
        if (isDisabled()) {
            return model;
        }
        String version = version(input, options);
        if (isRevision(model.getVersion())) {
            model.setVersion(version);
        }
        Parent parent = model.getParent();
        if (parent != null && isRevision(parent.getVersion())) {
            Parent resolvedParent = parent.clone();
            resolvedParent.setVersion(version);
            model.setParent(resolvedParent);
        }
        Properties properties = model.getProperties();
        if (properties == null) {
            properties = new Properties();
            model.setProperties(properties);
        }
        if (isRevision(properties.getProperty("revision"))) {
            properties.setProperty("revision", version);
        }
        return model;
    }

    private synchronized String version(File input, Map<String, ?> options) throws IOException {
        File root = repositoryRoot(input, options);
        if (!initialized || (calculatedRepositoryRoot == null && root != null)) {
            calculatedVersion = VersionResolver.resolve(root);
            calculatedRepositoryRoot = root;
            initialized = true;
        }
        return calculatedVersion;
    }

    private static boolean isRevision(String value) {
        return "${revision}".equals(value)
                || "0.0.0".equals(value)
                || "0.0.0-SNAPSHOT".equals(value)
                || "0.0.0-snapshot".equals(value);
    }

    private static boolean isDisabled() {
        return Boolean.parseBoolean(firstNonBlank(
                System.getProperty("versioning.disable"),
                System.getenv("VERSIONING_DISABLE"),
                "false"));
    }

    static File repositoryRoot(File input, Map<String, ?> options) {
        File root = findRepositoryRoot(input);
        if (root != null) {
            return root;
        }
        root = findRepositoryRoot(modelSourceFile(options));
        if (root != null) {
            return root;
        }
        root = findRepositoryRoot(propertyDirectory("maven.multiModuleProjectDirectory"));
        if (root != null) {
            return root;
        }
        return findRepositoryRoot(propertyDirectory("user.dir"));
    }

    private static File findRepositoryRoot(File location) {
        if (location == null) {
            return null;
        }
        File directory = location.getAbsoluteFile();
        if (!directory.isDirectory()) {
            directory = directory.getParentFile();
        }
        while (directory != null) {
            if (new File(directory, ".git").exists()) {
                return directory;
            }
            directory = directory.getParentFile();
        }
        return null;
    }

    private static File modelSourceFile(Map<String, ?> options) {
        if (options == null) {
            return null;
        }
        Object source = options.get(ModelProcessor.SOURCE);
        if (source instanceof FileSource) {
            return ((FileSource) source).getFile();
        }
        if (source instanceof Source) {
            String location = ((Source) source).getLocation();
            if (location != null && !location.trim().isEmpty()) {
                return new File(location);
            }
        }
        return null;
    }

    private static File propertyDirectory(String property) {
        String value = System.getProperty(property);
        return value == null || value.trim().isEmpty() ? null : new File(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
