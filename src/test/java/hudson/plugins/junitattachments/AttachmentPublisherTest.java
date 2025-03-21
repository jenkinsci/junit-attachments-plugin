package hudson.plugins.junitattachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.Builder;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.tasks.junit.PackageResult;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.test.TabulatedResult;
import hudson.tasks.test.TestResult;
import hudson.util.DescribableList;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import java.util.Map;

import org.jvnet.hudson.test.ExtractResourceSCM;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AttachmentPublisherTest {

    // Package name used in tests in workspace2.zip
    private static final String TEST_PACKAGE = "com.example.test";

    @Test
    void testWellKnownFilenamesAreAttached(JenkinsRule j) throws Exception {
        TestResultAction action = getTestResultActionForBuild(j, "workspace.zip", Result.SUCCESS);

        ClassResult cr = getClassResult(action, "test.foo.bar", "DefaultIntegrationTest");

        TestClassAttachmentTestAction ata = cr.getTestAction(TestClassAttachmentTestAction.class);
        assertNotNull(ata);

        final Map<String, List<String>> attachmentsByTestCase = ata.getAttachments();
        assertNotNull(attachmentsByTestCase);
        assertEquals(1, attachmentsByTestCase.size());

        List<String> testCaseAttachments = attachmentsByTestCase.get("");
        assertEquals(2, testCaseAttachments.size());
        Collections.sort(testCaseAttachments);
        assertEquals("file", testCaseAttachments.get(0));
        assertEquals("test.foo.bar.DefaultIntegrationTest-output.txt", testCaseAttachments.get(1));
    }

    @Test
    void testNoAttachmentsShownForPackage(JenkinsRule j) throws Exception {
        TestResultAction action = getTestResultActionForBuild(j, "workspace2.zip", Result.UNSTABLE);

        // At the package level, attachments shouldn't be shown
        PackageResult pr = action.getResult().byPackage(TEST_PACKAGE);
        AttachmentTestAction ata = pr.getTestAction(AttachmentTestAction.class);
        assertNull(ata);
    }

    //-------------------------------------------------------------------------------------

    // Tests that the correct summary of attachments are shown at the class level
    @Test
    void testAttachmentsShownForClass_SignupTest(JenkinsRule j) throws Exception {
        // There should be 5 attachments: 3 from the test methods, and 2 from the test suite
        //
        // The two testsuite files should come first, in order of appearance,
        // while the remaining files should appear in order of the test method name
        String[] expectedFiles = { "signup-suite-1", "signup-suite-2",
                "signup-reset", "signup-login", "signup-username" };
        runBuildAndAssertAttachmentsExist(j, "SignupTest", expectedFiles);
    }

    // Tests that the correct attachments are shown for individual test methods
    @Test
    void testAttachmentsShownForTestcases_SignupTest(JenkinsRule j) throws Exception {
        TestResultAction action = getTestResultActionForBuild(j, "workspace2.zip", Result.UNSTABLE);

        TabulatedResult classResult = getClassResult(action, "SignupTest");
        List<TestResult> cases = new ArrayList<>(classResult.getChildren());
        assertEquals(3, cases.size());

        // Each test case should have the respective one attachment
        String[] names = { "signup-reset", "signup-login", "signup-username" };
        for (int i = 0; i < cases.size(); i++) {
            assertAttachmentsExist(cases.get(i), new String[] { names[i] });
        }
    }

    // Tests that the correct attachments are shown for individual test methods with additional output prefix by ant/maven
    @Test
    void testAttachmentsShownForTestcases_SignupTest_WithRunnerPrefix(JenkinsRule j) throws Exception {
        TestResultAction action = getTestResultActionForBuild(j, "workspace3.zip", Result.UNSTABLE);

        TabulatedResult classResult = getClassResult(action, "SignupTest");
        List<TestResult> cases = new ArrayList<>(classResult.getChildren());
        assertEquals(3, cases.size());

        // Each test case should have the respective one attachment
        String[] names = { "signup-reset", "signup-login", "signup-username" };
        for (int i = 0; i < cases.size(); i++) {
            assertAttachmentsExist(cases.get(i), new String[] { names[i] });
        }
    }

    //-------------------------------------------------------------------------------------

    @Test
    void testAttachmentsShownForClass_LoginTest(JenkinsRule j) throws Exception {
        // There should be 2 attachments from the test methods
        String[] expectedFiles = { "login-reset", "login-password", "login-reset" };
        runBuildAndAssertAttachmentsExist(j, "LoginTest", expectedFiles);
    }

    @Test
    void testAttachmentsShownForTestcases_LoginTest(JenkinsRule j) throws Exception {
        TestResultAction action = getTestResultActionForBuild(j, "workspace2.zip", Result.UNSTABLE);

        TabulatedResult classResult = getClassResult(action, "LoginTest");
        List<TestResult> cases = new ArrayList<>(classResult.getChildren());
        assertEquals(4, cases.size());

        // Each test case should have the respective one (or zero) attachments
        String[] expectedFiles = { "login-reset", null, "login-password", "login-reset" };
        for (int i = 0; i < cases.size(); i++) {
            String expectedFile = expectedFiles[i];
            String[] files = expectedFile == null ? null : new String[] { expectedFile };
            assertAttachmentsExist(cases.get(i), files);
        }
    }

    //-------------------------------------------------------------------------------------

    @Test
    void testAttachmentsShownForClass_MiscTest1(JenkinsRule j) throws Exception {
        // There should be 2 attachments from the test suite
        String[] expectedFiles = { "misc-suite-1", "misc-suite-2" };
        runBuildAndAssertAttachmentsExist(j, "MiscTest1", expectedFiles);
    }

    // Individual test case should have no attachments, i.e. not overridden by class system-out
    @Test
    void testAttachmentsShownForTestcases_MiscTest1(JenkinsRule j) throws Exception {
        TestResultAction action = getTestResultActionForBuild(j, "workspace2.zip", Result.UNSTABLE);

        TabulatedResult classResult = getClassResult(action, "MiscTest1");
        List<TestResult> cases = new ArrayList<>(classResult.getChildren());
        assertEquals(1, cases.size());

        // Attachment should not be inherited from testsuite
        assertAttachmentsExist(cases.get(0), null);
    }

    //-------------------------------------------------------------------------------------

    @Test
    void testAttachmentsShownForClass_MiscTest2(JenkinsRule j) throws Exception {
        // There should be 6 attachments from the test suite, first stdout, then stderr,
        // followed by two from a single test case
        String[] expectedFiles = { "misc-suite-3", "misc-suite-4", "misc-suite-1", "misc-suite-2",
                "misc-something-1", "misc-something-2" };
        runBuildAndAssertAttachmentsExist(j, "MiscTest2", expectedFiles);
    }

    @Test
    void testAttachmentsShownForTestcases_MiscTest2(JenkinsRule j) throws Exception {
        TestResultAction action = getTestResultActionForBuild(j, "workspace2.zip", Result.UNSTABLE);

        TabulatedResult classResult = getClassResult(action, "MiscTest2");
        List<TestResult> cases = new ArrayList<>(classResult.getChildren());
        assertEquals(2, cases.size());

        // Alphabetically first comes the "doNothing" test
        assertAttachmentsExist(cases.get(0), null);
        // Followed by the "doSomething" test
        assertAttachmentsExist(cases.get(1), new String[] { "misc-something-1", "misc-something-2" });
    }

    @Test
    void testAttachmentsWithStrangeFileNames(JenkinsRule j) throws Exception {
        FreeStyleBuild build = getBuild(j, "workspace5.zip");

        HtmlPage page = j.createWebClient().withJavaScriptEnabled(false)
            .getPage(build, "testReport/com.example.test/SignupTest/");
        HtmlAnchor anchor1 = page.getAnchorByText("unicödeAndかわいいStuff");
        assertNotNull(anchor1.click());
        HtmlAnchor anchor2 = page.getAnchorByText("with space");
        assertNotNull(anchor2.click());
        HtmlAnchor anchor3 = page.getAnchorByText("special%§$_-%&[;]{}()char");
        assertNotNull(anchor3.click());
    }

    //-------------------------------------------------------------------------------------

    private static void runBuildAndAssertAttachmentsExist(JenkinsRule j, String className, String[] expectedFiles) throws Exception {
        TestResultAction action = getTestResultActionForBuild(j, "workspace2.zip", Result.UNSTABLE);

        ClassResult cr = getClassResult(action, className);
        assertAttachmentsExist(cr, expectedFiles);
    }

    // Asserts that, for the given TestResult, the given attachments exist
    private static void assertAttachmentsExist(TestResult result, String[] expectedFiles) {
        AttachmentTestAction ata = null;
        if (result instanceof ClassResult) {
            ata = result.getTestAction(AttachmentTestAction.class);
        }
        else if (result instanceof CaseResult) {
            ata = result.getTestAction(AttachmentTestAction.class);
        }

        if (expectedFiles == null) {
            assertNull(ata);
            return;
        }
        assertNotNull(ata);

        // Assert that attachments exist for this TestResult
        List<String> attachments;
        if (result instanceof ClassResult) {
            Map<String, List<String>> attachmentsByTestCase = ((TestClassAttachmentTestAction)ata).getAttachments();
            attachments = new ArrayList<>();
            for (List<String> list : attachmentsByTestCase.values()) {
                attachments.addAll(list);
            }
        }
        else {
            attachments = ((TestCaseAttachmentTestAction)ata).getAttachments();
        }

        assertNotNull(attachments);
        assertEquals(expectedFiles.length, attachments.size());

        // Assert that the expected files are there in the given order
        for (int i = 0; i < expectedFiles.length; i++) {
            assertEquals(expectedFiles[i], attachments.get(i));
        }
    }

    static ClassResult getClassResult(TestResultAction action, String className) {
        return getClassResult(action, TEST_PACKAGE, className);
    }

    static ClassResult getClassResult(TestResultAction action, String packageName, String className) {
        return action.getResult().byPackage(packageName).getClassResult(className);
    }

    private static FreeStyleBuild getBuild(JenkinsRule j, String workspaceZip) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();

        DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>> publishers =
		        new DescribableList<>(project);
        publishers.add(new AttachmentPublisher());

        project.setScm(new ExtractResourceSCM(AttachmentPublisherTest.class.getResource(workspaceZip)));
        project.getBuildersList().add(new TouchBuilder());
        JUnitResultArchiver archiver = new JUnitResultArchiver("*.xml");
        archiver.setTestDataPublishers(publishers);
        project.getPublishersList().add(archiver);

        return project.scheduleBuild2(0).get();
    }

    // Creates a job from the given workspace zip file, builds it and retrieves the TestResultAction
    private static TestResultAction getTestResultActionForBuild(JenkinsRule j, String workspaceZip, Result expectedStatus) throws Exception {
        FreeStyleBuild b = getBuild(j, workspaceZip);
        j.assertBuildStatus(expectedStatus, b);

        TestResultAction action = b.getAction(TestResultAction.class);
        assertNotNull(action);

        return action;
    }

    public static final class TouchBuilder extends Builder implements Serializable {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                BuildListener listener) throws InterruptedException,
                IOException {
            for (FilePath f : build.getWorkspace().list()) {
                f.touch(System.currentTimeMillis());
            }
            return true;
        }
    }

}
