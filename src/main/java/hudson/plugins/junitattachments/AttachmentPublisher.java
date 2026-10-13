package hudson.plugins.junitattachments;

import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.util.VirtualFile;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Descriptor;
import hudson.tasks.junit.TestAction;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.test.TestObject;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class AttachmentPublisher extends TestDataPublisher {

    /**
     * Name of the directory, relative to a build's artifact root, under which attachments
     * published via {@link jenkins.model.ArtifactManager} are stored.
     *
     * <p>Historical attachments predating this feature are instead stored directly under
     * the build's root directory (see {@link #getAttachmentPath(Run)}) and are not affected.
     */
    public static final String ARTIFACT_NAMESPACE = "junit-attachments";

    private Boolean showAttachmentsAtClassLevel = true;
    private Boolean showAttachmentsInStdOut = true;

    @DataBoundConstructor
    public AttachmentPublisher() {
    }

    public boolean isShowAttachmentsAtClassLevel() {
        return showAttachmentsAtClassLevel != null ? showAttachmentsAtClassLevel : true;
    }

    public boolean isShowAttachmentsInStdOut() {
        return showAttachmentsInStdOut != null ? showAttachmentsInStdOut : true;
    }

    @DataBoundSetter
    public void setShowAttachmentsAtClassLevel(Boolean showAttachmentsAtClassLevel) {
        this.showAttachmentsAtClassLevel = showAttachmentsAtClassLevel;
    }

    @DataBoundSetter
    public void setShowAttachmentsInStdOut(Boolean showAttachmentsInStdOut) {
        this.showAttachmentsInStdOut = showAttachmentsInStdOut;
    }

    /**
     * Returns the legacy, filesystem-based root under which attachments were stored directly in
     * the build's root directory before this plugin started publishing through the build's
     * {@link jenkins.model.ArtifactManager}.
     *
     * <p>Retained so that historical attachments remain readable; new attachments are no longer
     * written here.
     */
    public static FilePath getAttachmentPath(Run<?, ?> build) {
        return new FilePath(new File(build.getRootDir().getAbsolutePath()))
                .child(ARTIFACT_NAMESPACE);
    }

    public static FilePath getAttachmentPath(FilePath root, String className, String testName) {
        FilePath dir = root;
        for (String segment : pathSegments(className, testName)) {
            dir = dir.child(segment);
        }
        return dir;
    }

    /**
     * Resolves the directory for a given test class/test case underneath a {@link VirtualFile}
     * root, mirroring {@link #getAttachmentPath(FilePath, String, String)} for attachments stored
     * through an {@link jenkins.model.ArtifactManager}.
     */
    public static VirtualFile getAttachmentPath(VirtualFile root, String className, String testName) {
        VirtualFile dir = root;
        for (String segment : pathSegments(className, testName)) {
            dir = dir.child(segment);
        }
        return dir;
    }

    private static List<String> pathSegments(String className, String testName) {
        List<String> segments = new ArrayList<>();
        if (className != null && !className.isEmpty()) {
            segments.add(getStorageName(className));

            if (testName != null && !testName.isEmpty()) {
                segments.add(getStorageName(testName));
            }
        }
        return segments;
    }

    /**
     * The name of the directory that holds the attachments of the given test case.
     *
     * <p>Attachments are stored under a sanitised form of the name, so any URL pointing at them
     * has to be built from the same sanitised form rather than from the raw name.
     *
     * @param name the raw test case name
     * @return the directory name used to store its attachments
     */
    public static String getStorageName(String name) {
        return TestObject.safe(name).replace("\"", "");
    }

    @Override
    public Data contributeTestData(Run<?, ?> build, FilePath workspace, Launcher launcher,
                                   TaskListener listener, TestResult testResult) throws IOException,
            InterruptedException {
        final GetTestDataMethodObject methodObject = new GetTestDataMethodObject(build, workspace, launcher, listener, testResult);
        Map<String, Map<String, List<String>>> attachments = methodObject.getAttachments();

        if (attachments.isEmpty()) {
            return null;
        }

        return new Data(attachments, isShowAttachmentsAtClassLevel(), isShowAttachmentsInStdOut(), methodObject.getEnclosingBlocks(), true);
    }

    public static class Data extends TestResultAction.Data {

        @Deprecated
        private transient Map<String, List<String>> attachments;
        private Map<String, Map<String, List<String>>> attachmentsMap;
        private Boolean showAttachmentsAtClassLevel;
        private Boolean showAttachmentsInStdOut;
        private List<String> enclosingBlocks;

        /**
         * Whether the attachments referenced by this {@link Data} were published through the
         * build's {@link jenkins.model.ArtifactManager} (and are therefore resolved underneath
         * {@code ArtifactManager#root()}), as opposed to the legacy behaviour of storing them
         * directly in the build's root directory on the controller.
         *
         * <p>{@code null}/{@code false} means the legacy location is used; this is always the
         * case for historical builds predating this field.
         */
        private Boolean storedViaArtifactManager;

        /**
         * @param attachmentsMap { fully-qualified test class name → { test method name → [ attachment file name ] } }
         * @param showAttachmentsAtClassLevel Whether to display test case attachments at the test class level
         * @param enclosingBlocks Pipeline enclosing stages/blocks used to namespace storage and filter actions
         * @deprecated use {@link #Data(Map, Boolean, Boolean, List, boolean)}; attachments
         *      constructed via this constructor are always resolved from the legacy,
         *      filesystem-based storage location.
         */
        @Deprecated
        public Data(
                Map<String, Map<String, List<String>>> attachmentsMap,
                Boolean showAttachmentsAtClassLevel,
                Boolean showAttachmentsInStdOut,
                List<String> enclosingBlocks) {
            this(attachmentsMap, showAttachmentsAtClassLevel, showAttachmentsInStdOut, enclosingBlocks, false);
        }

        /**
         * @param attachmentsMap { fully-qualified test class name → { test method name → [ attachment file name ] } }
         * @param showAttachmentsAtClassLevel Whether to display test case attachments at the test class level
         * @param enclosingBlocks Pipeline enclosing stages/blocks used to namespace storage and filter actions
         * @param storedViaArtifactManager whether the referenced attachments were published
         *      through the build's {@link jenkins.model.ArtifactManager}
         */
        public Data(
                Map<String, Map<String, List<String>>> attachmentsMap,
                Boolean showAttachmentsAtClassLevel,
                Boolean showAttachmentsInStdOut,
                List<String> enclosingBlocks,
                boolean storedViaArtifactManager) {
            this.attachmentsMap = attachmentsMap;
            this.showAttachmentsAtClassLevel = showAttachmentsAtClassLevel;
            this.showAttachmentsInStdOut = showAttachmentsInStdOut;
            this.enclosingBlocks = enclosingBlocks == null ? null : new ArrayList<>(enclosingBlocks);
            this.storedViaArtifactManager = storedViaArtifactManager;
        }

        public boolean isStoredViaArtifactManager() {
            return storedViaArtifactManager != null && storedViaArtifactManager;
        }

        @Override
        @SuppressWarnings("deprecation")
        public List<TestAction> getTestAction(hudson.tasks.junit.TestObject t) {
            TestObject testObject = (TestObject) t;

            final String packageName;
            final String className;
            final String testName;

            if (testObject instanceof ClassResult) {
                // We're looking at the page for a test class (i.e. a single TestCase)
                if (!showAttachmentsAtClassLevel) {
                    return Collections.emptyList();
                }

                // If enclosingBlocks is non-empty, check that at least one child CaseResult matches
                if (enclosingBlocks != null && !enclosingBlocks.isEmpty()) {
                    ClassResult classResult = (ClassResult) testObject;
                    boolean found = false;
                    for (CaseResult child : classResult.getChildren()) {
                        if (enclosingBlocks.equals(child.getSuiteResult().getEnclosingBlocks())) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        return Collections.emptyList();
                    }
                }

                packageName = testObject.getParent().getName();
                className = testObject.getName();
                testName = null;
            } else if (testObject instanceof CaseResult caseResult) {
                // We're looking at the page for an individual test (i.e. a single @Test method)

                // If enclosingBlocks is non-empty, filter by matching enclosing flow node IDs
                if (enclosingBlocks != null && !enclosingBlocks.isEmpty()) {
                    if (!enclosingBlocks.equals(caseResult.getSuiteResult().getEnclosingBlocks())) {
                        return Collections.emptyList();
                    }
                }

                packageName = testObject.getParent().getParent().getName();
                className = testObject.getParent().getName();
                testName = testObject.getName();
            } else {
                // Otherwise, we don't want to show any attachments (e.g. at the package level)
                return Collections.emptyList();
            }

            // Determine the fully-qualified test class (i.e. com.example.foo.MyTestCase)
            String fullName = getFullyQualifiedTestClassName(packageName, className);

            // Get the mapping of individual test -> attachment names
            Map<String, List<String>> tests = attachmentsMap.get(fullName);
            if (tests == null) {
                return Collections.emptyList();
            }

            // Attachments published through the build's ArtifactManager are resolved underneath
            // its artifact root; historical attachments are resolved from the legacy,
            // filesystem-based location directly under the build's root directory.
            VirtualFile root = isStoredViaArtifactManager()
                    ? testObject.getRun().getArtifactManager().root().child(ARTIFACT_NAMESPACE)
                    : getAttachmentPath(testObject.getRun()).toVirtualFile();
            if (enclosingBlocks != null && !enclosingBlocks.isEmpty()) {
                root = root.child(String.join("-", enclosingBlocks));
            }
            // Historical builds might have attachments stored in class level directories.
            // Attachments published through the ArtifactManager always use the current,
            // unambiguous class/test layout, so no such detection is needed for them.
            boolean attachmentsStoredAtClassLevel = !isStoredViaArtifactManager()
                    && enclosingBlocks == null && areAttachmentsStoredAtClassLevel(root, fullName, tests);

            // Return a single TestAction which will display the attached files
            AttachmentTestAction action;
            if (testObject instanceof ClassResult cr) {
                // Ensure attachments are shown in the same order as the tests
                TreeMap<String, List<String>> sortedTests = new TreeMap<String, List<String>>(tests);

                action = new TestClassAttachmentTestAction(
                        cr,
                        getAttachmentPath(root, fullName, null),
                        sortedTests,
                        attachmentsStoredAtClassLevel,
                        enclosingBlocks);
            }
            else {
                List<String> attachmentPaths = tests.get(testName);
                if (attachmentPaths == null || attachmentPaths.isEmpty()) {
                    return Collections.emptyList();
                }

                VirtualFile attachmentsDirectory = attachmentsStoredAtClassLevel ?
                        getAttachmentPath(root, fullName, null) :
                        getAttachmentPath(root, fullName, testName);

                action = new TestCaseAttachmentTestAction(
                        (CaseResult) testObject, attachmentsDirectory, attachmentPaths, showAttachmentsInStdOut);
            }

            return Collections.<TestAction> singletonList(action);
        }

        /** Handles migration from the old serialisation format. */
        private Object readResolve() {
            if (this.showAttachmentsAtClassLevel == null) {
                this.showAttachmentsAtClassLevel = true;
            }

            if (this.showAttachmentsInStdOut == null) {
                this.showAttachmentsInStdOut = true;
            }

            if (this.storedViaArtifactManager == null) {
                // Builds serialized before this field was introduced always used the legacy,
                // filesystem-based storage location.
                this.storedViaArtifactManager = Boolean.FALSE;
            }

            if (attachments != null && attachmentsMap == null) {
                // Migrate from the flat list per test class to a map of <test method, attachments>
                attachmentsMap = new HashMap<String, Map<String, List<String>>>();

                // Previously, there was no mapping between individual tests and their attachments,
                // so here we just associate all attachments with an empty-named test method.
                //
                // This means that all attachments will appear on the test class page as before,
                // but they won't also be repeated on each individual test method's page
                for (Map.Entry<String,List<String>> entry : attachments.entrySet()) {
                    HashMap<String, List<String>> testMap = new HashMap<String, List<String>>();
                    testMap.put("", entry.getValue());
                    attachmentsMap.put(entry.getKey(), testMap);
                }
                attachments = null;
            }

            return this;
        }

        private static String getFullyQualifiedTestClassName(String packageName, String className) {
            String fullName = "";
            if (!packageName.equals("(root)")) {
                fullName += packageName;
                fullName += ".";
            }
            fullName += className;

            return fullName;
        }

        private boolean areAttachmentsStoredAtClassLevel(
                VirtualFile root, String fullName, Map<String, List<String>> classAttachments) {

            for (Map.Entry<String,List<String>> entry : classAttachments.entrySet()) {
                for (String attachment : entry.getValue()) {
                    VirtualFile testCaseAttachmentsDirectory = getAttachmentPath(root, fullName, entry.getKey());
                    VirtualFile testCaseAttachmentPath = testCaseAttachmentsDirectory.child(attachment);
                    try {
                        if (testCaseAttachmentPath.exists()) {
                            return false;
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            return  true;
        }
    }

    @Extension
    @Symbol("attachments")
    public static class DescriptorImpl extends Descriptor<TestDataPublisher> {

        @Override
        public String getDisplayName() {
            return "Publish test attachments";
        }

    }
}
