package hudson.plugins.junitattachments;

import org.jvnet.hudson.test.ExtractResourceSCM;
import org.jvnet.hudson.test.HudsonTestCase;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.tasks.Builder;
import hudson.tasks.junit.PackageResult;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.tasks.test.TestResult;
import hudson.util.DescribableList;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

public class AttachmentPublisherTest extends HudsonTestCase {

    // Package name used in tests in workspace2.zip
    private static final String TEST_PACKAGE = "com.example.test";

    public void testWellKnownFilenamesAreAttached() throws Exception {
        TestResultAction action = getTestResultActionForBuild("workspace.zip", Result.SUCCESS);

        ClassResult cr = getClassResult(action, "test.foo.bar", "DefaultIntegrationTest");

        AttachmentTestAction ata = cr.getTestAction(AttachmentTestAction.class);
        assertNotNull(ata);

        final List<String> attachments = ata.getAttachments();
        assertNotNull(attachments);
        assertEquals(2, attachments.size());

        Collections.sort(attachments);
        assertEquals("file", attachments.get(0));
        assertEquals("test.foo.bar.DefaultIntegrationTest-output.txt", attachments.get(1));
    }

    public void testNoAttachmentsShownForPackage() throws Exception {
        TestResultAction action = getTestResultActionForBuild("workspace2.zip", Result.UNSTABLE);

        // At the package level, attachments shouldn't be shown
        PackageResult pr = action.getResult().byPackage(TEST_PACKAGE);
        AttachmentTestAction ata = pr.getTestAction(AttachmentTestAction.class);
        assertNull(ata);
    }

    //-------------------------------------------------------------------------------------

    // Tests that the correct summary of attachments are shown at the class level
    public void testAttachmentsShownForClass_SignupTest() throws Exception {
        // There should be 5 attachments: 3 from the test methods, and 2 from the test suite
        //
        // The two testsuite files should come first, in order of appearance,
        // while the remaining files should appear in order of the test method name
        String[] expectedFiles = { "signup-suite-1", "signup-suite-2",
                "signup-reset", "signup-login", "signup-username" };
        runBuildAndAssertAttachmentsExist("SignupTest", expectedFiles);
    }

    // Tests that the correct attachments are shown for individual test methods
    public void testAttachmentsShownForTestcases_SignupTest() throws Exception {
        TestResultAction action = getTestResultActionForBuild("workspace2.zip", Result.UNSTABLE);

        ClassResult classResult = getClassResult(action, "SignupTest");
        List<CaseResult> cases = classResult.getChildren();
        assertEquals(3, cases.size());

        // Each test case should have the respective one attachment
        String[] names = { "signup-reset", "signup-login", "signup-username" };
        for (int i = 0; i < cases.size(); i++) {
            assertAttachmentsExist(cases.get(i), new String[] { names[i] });
        }
    }
    // Tests that the correct attachments are shown for individual test methods with additional output prefix by ant/maven
    public void testAttachmentsShownForTestcases_SignupTest_WithRunnerPrefix() throws Exception {
        TestResultAction action = getTestResultActionForBuild("workspace3.zip", Result.UNSTABLE);

        ClassResult classResult = getClassResult(action, "SignupTest");
        List<CaseResult> cases = classResult.getChildren();
        assertEquals(3, cases.size());

        // Each test case should have the respective one attachment
        String[] names = { "signup-reset", "signup-login", "signup-username" };
        for (int i = 0; i < cases.size(); i++) {
            assertAttachmentsExist(cases.get(i), new String[] { names[i] });
        }
    }

    //-------------------------------------------------------------------------------------

    public void testAttachmentsShownForClass_LoginTest() throws Exception {
        // There should be 2 attachments from the test methods
        String[] expectedFiles = { "login-reset", "login-password" };
        runBuildAndAssertAttachmentsExist("LoginTest", expectedFiles);
    }

    public void testAttachmentsShownForTestcases_LoginTest() throws Exception {
        TestResultAction action = getTestResultActionForBuild("workspace2.zip", Result.UNSTABLE);

        ClassResult classResult = getClassResult(action, "LoginTest");
        List<CaseResult> cases = classResult.getChildren();
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

    public void testAttachmentsShownForClass_MiscTest1() throws Exception {
        // There should be 2 attachments from the test suite
        String[] expectedFiles = { "misc-suite-1", "misc-suite-2" };
        runBuildAndAssertAttachmentsExist("MiscTest1", expectedFiles);
    }

    // Individual test case should have no attachments, i.e. not overridden by class system-out
    public void testAttachmentsShownForTestcases_MiscTest1() throws Exception {
        TestResultAction action = getTestResultActionForBuild("workspace2.zip", Result.UNSTABLE);

        ClassResult classResult = getClassResult(action, "MiscTest1");
        List<CaseResult> cases = classResult.getChildren();
        assertEquals(1, cases.size());

        // Attachment should not be inherited from testsuite
        assertAttachmentsExist(cases.get(0), null);
    }

    //-------------------------------------------------------------------------------------

    public void testAttachmentsShownForClass_MiscTest2() throws Exception {
        // There should be 6 attachments from the test suite, first stdout, then stderr,
        // followed by two from a single test case
        String[] expectedFiles = { "misc-suite-3", "misc-suite-4", "misc-suite-1", "misc-suite-2",
                "misc-something-1", "misc-something-2" };
        runBuildAndAssertAttachmentsExist("MiscTest2", expectedFiles);
    }

    public void testAttachmentsShownForTestcases_MiscTest2() throws Exception {
        TestResultAction action = getTestResultActionForBuild("workspace2.zip", Result.UNSTABLE);

        ClassResult classResult = getClassResult(action, "MiscTest2");
        List<CaseResult> cases = classResult.getChildren();
        assertEquals(2, cases.size());

        // Alphabetically first comes the "doNothing" test
        assertAttachmentsExist(cases.get(0), null);
        // Followed by the "doSomething" test
        assertAttachmentsExist(cases.get(1), new String[] { "misc-something-1", "misc-something-2" });
    }

    //-------------------------------------------------------------------------------------

    private void runBuildAndAssertAttachmentsExist(String className, String[] expectedFiles) throws Exception {
        TestResultAction action = getTestResultActionForBuild("workspace2.zip", Result.UNSTABLE);

        ClassResult cr = getClassResult(action, className);
        assertAttachmentsExist(cr, expectedFiles);
    }

    // Asserts that, for the given TestResult, the given attachments exist
    private static void assertAttachmentsExist(TestResult result, String[] expectedFiles) {
        AttachmentTestAction ata = result.getTestAction(AttachmentTestAction.class);
        if (expectedFiles == null) {
            assertNull(ata);
            return;
        }
        assertNotNull(ata);

        // Assert that attachments exist for this TestResult
        List<String> attachments = ata.getAttachments();
        assertNotNull(attachments);
        assertEquals(expectedFiles.length, attachments.size());

        // Assert that the expected files are there in the given order
        for (int i = 0; i < expectedFiles.length; i++) {
            assertEquals(expectedFiles[i], attachments.get(i));
        }
    }

    private static ClassResult getClassResult(TestResultAction action, String className) {
        return getClassResult(action, TEST_PACKAGE, className);
    }

    private static ClassResult getClassResult(TestResultAction action, String packageName, String className) {
        return action.getResult().byPackage(packageName).getClassResult(className);
    }

    // Creates a job from the given workspace zip file, builds it and retrieves the TestResultAction
    private TestResultAction getTestResultActionForBuild(String workspaceZip, Result expectedStatus) throws Exception {
        FreeStyleProject project = createFreeStyleProject();

        DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>> publishers =
                new DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>>(project);
        publishers.add(new AttachmentPublisher());

        project.setScm(new ExtractResourceSCM(getClass().getResource(workspaceZip)));
        project.getBuildersList().add(new TouchBuilder());
        JUnitResultArchiver archiver = new JUnitResultArchiver("*.xml", false, publishers);
        project.getPublishersList().add(archiver);

        FreeStyleBuild b = project.scheduleBuild2(0).get();
        assertBuildStatus(expectedStatus, b);

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
