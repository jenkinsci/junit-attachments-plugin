package hudson.plugins.junitattachments;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Run;
import jenkins.model.ArtifactManager;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import jenkins.util.VirtualFile;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Test-only {@link ArtifactManagerFactory} backed entirely by an in-memory map, used to verify
 * that this plugin publishes attachments exclusively through the {@link ArtifactManager} API
 * (i.e. without writing any payload to the controller's local build folder), the way a remote
 * provider such as the Azure Artifact Manager plugin would.
 *
 * <p>Each instance is independent (tests should construct a fresh one and register it with
 * {@link jenkins.model.ArtifactManagerConfiguration#getArtifactManagerFactories()}), and all
 * archived bytes live only in {@link #files}, never on disk.
 */
final class MemoryArtifactManagerFactory extends ArtifactManagerFactory {

    /** Archived bytes, keyed by their full, {@code /}-separated archive-relative path. */
    final Map<String, byte[]> files = new LinkedHashMap<>();

    /** Every {@code artifacts} map passed to {@link ArtifactManager#archive}, in call order. */
    final List<Map<String, String>> archiveCalls = new ArrayList<>();

    /** When non-null, {@link ArtifactManager#archive} throws this instead of archiving. */
    volatile IOException failArchiveWith;

    @Override
    public ArtifactManager managerFor(Run<?, ?> build) {
        return new MemoryArtifactManager();
    }

    final class MemoryArtifactManager extends ArtifactManager {

        @Override
        public void onLoad(Run<?, ?> build) {
            // Nothing to restore: all state lives in the enclosing factory instance, which the
            // test keeps a reference to directly.
        }

        @Override
        public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String, String> artifacts)
                throws IOException, InterruptedException {
            if (failArchiveWith != null) {
                throw failArchiveWith;
            }
            archiveCalls.add(new LinkedHashMap<>(artifacts));
            for (Map.Entry<String, String> entry : artifacts.entrySet()) {
                String archivePath = entry.getKey();
                String sourceRelativePath = entry.getValue();
                FilePath src = workspace.child(sourceRelativePath);
                try (InputStream in = src.read()) {
                    files.put(archivePath, in.readAllBytes());
                }
            }
        }

        @Override
        public boolean delete() {
            boolean hadFiles = !files.isEmpty();
            files.clear();
            return hadFiles;
        }

        @Override
        public VirtualFile root() {
            return new MemoryVirtualFile("");
        }
    }

    /** A {@link VirtualFile} view over a subtree of {@link #files}. */
    final class MemoryVirtualFile extends VirtualFile {

        @Serial
        private static final long serialVersionUID = 1L;

        /** {@code /}-separated path relative to the archive root; {@code ""} denotes the root. */
        private final String path;

        MemoryVirtualFile(String path) {
            this.path = path;
        }

        @Override
        public String getName() {
            int i = path.lastIndexOf('/');
            return i < 0 ? path : path.substring(i + 1);
        }

        @Override
        public URI toURI() {
            return URI.create("memory:/" + path);
        }

        @Override
        public VirtualFile getParent() {
            int i = path.lastIndexOf('/');
            return i < 0 ? new MemoryVirtualFile("") : new MemoryVirtualFile(path.substring(0, i));
        }

        @Override
        public boolean isDirectory() {
            if (path.isEmpty()) {
                return true;
            }
            if (files.containsKey(path)) {
                return false;
            }
            String prefix = path + "/";
            return files.keySet().stream().anyMatch(k -> k.startsWith(prefix));
        }

        @Override
        public boolean isFile() {
            return files.containsKey(path);
        }

        @Override
        public boolean exists() {
            return path.isEmpty() || files.containsKey(path) || isDirectory();
        }

        @Override
        public VirtualFile[] list() {
            String prefix = path.isEmpty() ? "" : path + "/";
            Set<String> childNames = new TreeSet<>();
            for (String k : files.keySet()) {
                if (k.startsWith(prefix)) {
                    String rest = k.substring(prefix.length());
                    int slash = rest.indexOf('/');
                    childNames.add(slash < 0 ? rest : rest.substring(0, slash));
                }
            }
            Set<MemoryVirtualFile> children = new LinkedHashSet<>();
            for (String name : childNames) {
                children.add(new MemoryVirtualFile(prefix + name));
            }
            return children.toArray(new VirtualFile[0]);
        }

        @Override
        public VirtualFile child(String name) {
            String childPath = path.isEmpty() ? name : path + "/" + name;
            return new MemoryVirtualFile(childPath);
        }

        @Override
        public long length() {
            byte[] bytes = files.get(path);
            return bytes == null ? 0 : bytes.length;
        }

        @Override
        public long lastModified() {
            return 0;
        }

        @Override
        public boolean canRead() {
            return files.containsKey(path);
        }

        @Override
        public InputStream open() throws IOException {
            byte[] bytes = files.get(path);
            if (bytes == null) {
                throw new FileNotFoundException(path);
            }
            return new ByteArrayInputStream(bytes);
        }

    }

    @Extension
    public static final class DescriptorImpl extends ArtifactManagerFactoryDescriptor {
        @Override
        public String getDisplayName() {
            return "In-memory (test only)";
        }
    }
}
