package hudson.plugins.junitattachments;

import hudson.model.*;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
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
import java.util.*;

public class AttachmentPublisher extends TestDataPublisher {

    private Boolean showAttachmentsAtClassLevel = true;

    @DataBoundConstructor
    public AttachmentPublisher() {
    }

    public boolean isShowAttachmentsAtClassLevel() {
        return showAttachmentsAtClassLevel != null ? showAttachmentsAtClassLevel : true;
    }

    @DataBoundSetter
    public void setShowAttachmentsAtClassLevel(Boolean showAttachmentsAtClassLevel) {
        this.showAttachmentsAtClassLevel = showAttachmentsAtClassLevel;
    }

    protected Object readResolve() {
        if (this.showAttachmentsAtClassLevel == null) {
            this.showAttachmentsAtClassLevel = true;
        }
        return this;
    }

    public static FilePath getAttachmentPath(Run<?, ?> build) {
        return new FilePath(new File(build.getRootDir().getAbsolutePath()))
                .child("junit-attachments");
    }

    public static FilePath getAttachmentPath(FilePath root, String child) {
        FilePath dir = root;
        if (!StringUtils.isEmpty(child)) {
            dir = dir.child(TestObject.safe(child));
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

        return new Data(attachments, this.showAttachmentsAtClassLevel);
    }

    public static class Data extends TestResultAction.Data {

        @Deprecated
        private transient Map<String, List<String>> attachments;
        private Map<String, Map<String, List<String>>> attachmentsMap;
        private final boolean showAttachmentsAtClassLevel;

        /**
         * @param attachmentsMap { fully-qualified test class name → { test method name → [ attachment file name ] } }
         */
        public Data(
                Map<String, Map<String, List<String>>> attachmentsMap,
                boolean showAttachmentsAtClassLevel) {
            this.attachmentsMap = attachmentsMap;
            this.showAttachmentsAtClassLevel = showAttachmentsAtClassLevel;
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

                packageName = testObject.getParent().getName();
                className = testObject.getName();
                testName = null;
            } else if (testObject instanceof CaseResult) {
                // We're looking at the page for an individual test (i.e. a single @Test method)
                packageName = testObject.getParent().getParent().getName();
                className = testObject.getParent().getName();
                testName = testObject.getName();
            } else {
                // Otherwise, we don't want to show any attachments (e.g. at the package level)
                return Collections.emptyList();
            }

            // Determine the fully-qualified test class (i.e. com.example.foo.MyTestCase)
            String fullName = "";
            if (!packageName.equals("(root)")) {
                fullName += packageName;
                fullName += ".";
            }
            fullName += className;

            // Get the mapping of individual test -> attachment names
            Map<String, List<String>> tests = attachmentsMap.get(fullName);
            if (tests == null) {
                return Collections.emptyList();
            }

            List<String> attachmentPaths;
            if (testName == null) {
                // If we're looking at the page for the test class, rather than an individual test
                // method, then gather together the set of attachments from all of its test methods
                LinkedHashSet<String> paths = new LinkedHashSet<String>();

                // Ensure attachments are shown in the same order as the tests
                TreeMap<String, List<String>> sortedTests = new TreeMap<String, List<String>>(tests);
                for (List<String> testList : sortedTests.values()) {
                    paths.addAll(testList);
                }
                attachmentPaths = new ArrayList<String>(paths);
            } else {
                attachmentPaths = tests.get(testName);
            }

            // If we didn't find anything for this test class or test method, give up
            if (attachmentPaths == null || attachmentPaths.isEmpty()) {
                return Collections.emptyList();
            }

            // Return a single TestAction which will display the attached files
            FilePath root = getAttachmentPath(testObject.getRun());
            AttachmentTestAction action = new AttachmentTestAction(testObject,
                    getAttachmentPath(root, fullName), attachmentPaths);
            return Collections.<TestAction> singletonList(action);
        }

        /** Handles migration from the old serialisation format. */
        private Object readResolve() {
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
