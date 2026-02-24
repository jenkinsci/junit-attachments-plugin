package hudson.plugins.junitattachments;

import hudson.model.Run;
import hudson.model.TaskListener;
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

    public static FilePath getAttachmentPath(Run<?, ?> build) {
        return new FilePath(new File(build.getRootDir().getAbsolutePath()))
                .child("junit-attachments");
    }

    public static FilePath getAttachmentPath(FilePath root, String className, String testName) {
        FilePath dir = root;
        if (className != null && !className.isEmpty()) {
            dir = dir.child(TestObject.safe(className));

            if (testName != null && !testName.isEmpty()) {
                dir = dir.child(TestObject.safe(testName).replace("\"", ""));
            }
        }
        return dir;
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

        return new Data(attachments, isShowAttachmentsAtClassLevel(), isShowAttachmentsInStdOut(), methodObject.getEnclosingBlocks());
    }

    public static class Data extends TestResultAction.Data {

        @Deprecated
        private transient Map<String, List<String>> attachments;
        private Map<String, Map<String, List<String>>> attachmentsMap;
        private Boolean showAttachmentsAtClassLevel;
        private Boolean showAttachmentsInStdOut;
        private List<String> enclosingBlocks;

        /**
         * @param attachmentsMap { fully-qualified test class name → { test method name → [ attachment file name ] } }
         * @param showAttachmentsAtClassLevel Whether to display test case attachments at the test class level
         * @param enclosingBlocks Pipeline enclosing stages/blocks used to namespace storage and filter actions
         */
        public Data(
                Map<String, Map<String, List<String>>> attachmentsMap,
                Boolean showAttachmentsAtClassLevel,
                Boolean showAttachmentsInStdOut,
                List<String> enclosingBlocks) {
            this.attachmentsMap = attachmentsMap;
            this.showAttachmentsAtClassLevel = showAttachmentsAtClassLevel;
            this.showAttachmentsInStdOut = showAttachmentsInStdOut;
            this.enclosingBlocks = enclosingBlocks == null ? null : new ArrayList<>(enclosingBlocks);
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

            FilePath root = getAttachmentPath(testObject.getRun());
            if (enclosingBlocks != null && !enclosingBlocks.isEmpty()) {
                root = root.child(String.join("-", enclosingBlocks));
            }
            // Historical builds might have attachments stored in class level directories
            boolean attachmentsStoredAtClassLevel = enclosingBlocks == null && areAttachmentsStoredAtClassLevel(root, fullName, tests);

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

                FilePath attachmentsDirectory = attachmentsStoredAtClassLevel ?
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
                FilePath root, String fullName, Map<String, List<String>> classAttachments) {

            for (Map.Entry<String,List<String>> entry : classAttachments.entrySet()) {
                for (String attachment : entry.getValue()) {
                    FilePath testCaseAttachmentsDirectory = getAttachmentPath(root, fullName, entry.getKey());
                    var testCaseAttachmentPath = new FilePath(testCaseAttachmentsDirectory, attachment);
                    try {
                        if (testCaseAttachmentPath.exists()) {
                            return false;
                        }
                    } catch (IOException | InterruptedException e) {
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
