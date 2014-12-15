package hudson.plugins.junitattachments;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.tasks.junit.TestAction;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.test.TestObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

public class AttachmentPublisher extends TestDataPublisher {

    @DataBoundConstructor
    public AttachmentPublisher() {
    }

    public static FilePath getAttachmentPath(AbstractBuild<?, ?> build) {
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
    public Data getTestData(AbstractBuild<?, ?> build, Launcher launcher,
            BuildListener listener, TestResult testResult) throws IOException,
            InterruptedException {
        final GetTestDataMethodObject methodObject = new GetTestDataMethodObject(build, launcher, listener, testResult);
        Map<String, Map<String, List<String>>> attachments = methodObject.getAttachments();

        if (attachments.isEmpty()) {
            return null;
        }

        return new Data(attachments);
    }

    public static class Data extends TestResultAction.Data {

        @Deprecated
        private transient Map<String, List<String>> attachments;
        private Map<String, Map<String, List<String>>> attachmentsMap;

        /**
         * @param attachmentsMap { fully-qualified test class name -> { test method name -> [ attachment file name ] } }
         */
        public Data(Map<String, Map<String, List<String>>> attachmentsMap) {
            this.attachmentsMap = attachmentsMap;
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
            FilePath root = getAttachmentPath(testObject.getOwner());
			// Modules with junit is not currently managed correctly
			try {
				if (!root.exists()) {
					String remote = root.getRemote();
					Pattern jobs = Pattern.compile("^(.*)\\/jobs\\/(.*)\\/modules\\/.*\\/builds\\/(.*)$");
					Matcher match = jobs.matcher(remote);
					if (match.matches()) {
						FilePath newRoot = new FilePath(new File(match.group(1) + "/jobs/" + match.group(2)
								+ "/builds/" + match.group(3)));
						if (newRoot.exists()) {
							root = newRoot;
						}
					}
				}
			} catch (Exception e) {
				// No ops
			}

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
                for (String testClass : attachments.keySet()) {
                    HashMap<String, List<String>> testMap = new HashMap<String, List<String>>();
                    testMap.put("", attachments.get(testClass));
                    attachmentsMap.put(testClass, testMap);
                }
                attachments = null;
            }

            return this;
        }

    }

    @Extension
    public static class DescriptorImpl extends Descriptor<TestDataPublisher> {

        @Override
        public String getDisplayName() {
            return "Publish test attachments";
        }

    }

}
