/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */
package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.model.Result;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.test.TestResult;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static hudson.plugins.junitattachments.AttachmentPublisherTest.getClassResult;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class AttachmentPublisherPipelineTest {
    // Package name used in tests in workspace2.zip
    private static final String TEST_PACKAGE = "com.example.test";

    @Test
    void testWellKnownFilenamesAreAttached(JenkinsRule jenkinsRule) throws Exception {
        TestResultAction action = getTestResultActionForPipeline(jenkinsRule, "workspace.zip", "pipelineTest.groovy", Result.SUCCESS);

        ClassResult cr = getClassResult(action, "test.foo.bar", "DefaultIntegrationTest");

        TestClassAttachmentTestAction ata = cr.getTestAction(TestClassAttachmentTestAction.class);
        assertNotNull(ata);

        Map<String, List<String>> attachmentsByTestCase = ata.getAttachments();
        assertNotNull(attachmentsByTestCase);
        assertEquals(1, attachmentsByTestCase.size());

        List<String> testCaseAttachments = attachmentsByTestCase.get("");
        assertEquals(2, testCaseAttachments.size());
        Collections.sort(testCaseAttachments);
        assertEquals("file", testCaseAttachments.get(0));
        assertEquals("test.foo.bar.DefaultIntegrationTest-output.txt", testCaseAttachments.get(1));
    }

    @Issue("JENKINS-36504")
    @Test
    void annotationDoesNotFailForPipeline(JenkinsRule jenkinsRule) throws Exception {
        TestResultAction action = getTestResultActionForPipeline(jenkinsRule, "workspace2.zip", "pipelineTest.groovy", Result.UNSTABLE);

        ClassResult cr = getClassResult(action, TEST_PACKAGE, "SignupTest");
        Collection<? extends TestResult> caseResults = cr.getChildren();
        assertEquals(3, caseResults.size());

        CaseResult failingCase = cr.getCaseResult("A_003_Type_the_text__jenkins__into_the_field__username_");
        assertNotNull(failingCase);
        assertEquals("Timed out after 10 seconds", failingCase.annotate(failingCase.getErrorDetails()));

        TestCaseAttachmentTestAction ata = failingCase.getTestAction(TestCaseAttachmentTestAction.class);
        assertNotNull(ata);

        final List<String> attachments = ata.getAttachments();
        assertNotNull(attachments);
        assertEquals(1, attachments.size());

        Collections.sort(attachments);
        assertEquals("signup-username", attachments.get(0));
    }

    @Test
    void testBothWellKnownFilenamesAndPatternAreAttached(JenkinsRule jenkinsRule) throws Exception {
        TestResultAction action = getTestResultActionForPipeline(jenkinsRule, "workspace4.zip", "pipelineTest.groovy", Result.SUCCESS);

        ClassResult cr = getClassResult(action, "test.foo.bar", "DefaultIntegrationTest");
        {
            TestClassAttachmentTestAction ata = cr.getTestAction(TestClassAttachmentTestAction.class);
            assertNotNull(ata);
            final Map<String, List<String>> attachmentsByTestCase = ata.getAttachments();
            assertNotNull(attachmentsByTestCase);
            assertEquals(2, attachmentsByTestCase.size());

            List<String> testClassAttachments = attachmentsByTestCase.get("");
            assertEquals(3, testClassAttachments.size());
            Collections.sort(testClassAttachments);
            assertEquals(Paths.get("experimentsWithJavaElements", "attachment.txt").toString(), testClassAttachments.get(0));
            assertEquals("file", testClassAttachments.get(1));
            assertEquals("test.foo.bar.DefaultIntegrationTest-output.txt", testClassAttachments.get(2));
        }

        CaseResult caseResult = cr.getCaseResult("experimentsWithJavaElements");
        {
            TestCaseAttachmentTestAction caseAta = caseResult.getTestAction(TestCaseAttachmentTestAction.class);
            assertNotNull(caseAta);
            final List<String> caseAttachments = caseAta.getAttachments();
            assertNotNull(caseAttachments);
            assertEquals(1, caseAttachments.size());
            assertEquals("attachment.txt", caseAttachments.get(0));
        }
    }

    @Test
    @Issue("https://github.com/jenkinsci/junit-attachments-plugin/issues/202")
    void testMultipleTestExecutions(JenkinsRule jenkinsRule) throws Exception {
        WorkflowRun run = buildParallelBranchesProject(jenkinsRule);
        TestResultAction tra = run.getAction(TestResultAction.class);
        List<CaseResult> passedTests = tra.getPassedTests();
        assertThat(passedTests, hasSize(2));
        boolean foundFirstBranch = false;
        boolean foundSecondBranch = false;

        for (CaseResult cr : passedTests) {
            // which branch was this in
            List<String> branchNames = cr.getEnclosingFlowNodeNames();
            if (branchNames.contains("firstBranch")) {
                foundFirstBranch = true;
                assertThat(getTestAttachmentAsText(jenkinsRule, cr), is("this is branch firstBranch"));
            }
            if (branchNames.contains("secondBranch")) {
                foundSecondBranch = true;
                assertThat(getTestAttachmentAsText(jenkinsRule, cr), is("this is branch secondBranch"));
            }
        }
        assertTrue(foundFirstBranch, "Found first branch");
        assertTrue(foundSecondBranch, "Found second branch");
    }

    @Test
    @Issue("https://github.com/jenkinsci/junit-attachments-plugin/issues/202")
    void testMultipleTestExecutionsClassResult(JenkinsRule jenkinsRule) throws Exception {
        WorkflowRun run = buildParallelBranchesProject(jenkinsRule);
        TestResultAction tra = run.getAction(TestResultAction.class);

        ClassResult classResult = getClassResult(tra, "com.example", "MyTest");
        assertNotNull(classResult);

        // Each branch produces one Data, and each Data should produce one TestClassAttachmentTestAction
        // for the ClassResult (since the ClassResult has children from both branches).
        List<TestClassAttachmentTestAction> classActions = classResult.getTestActions().stream()
                .filter(TestClassAttachmentTestAction.class::isInstance)
                .map(TestClassAttachmentTestAction.class::cast)
                .collect(Collectors.toList());
        assertThat(classActions, hasSize(2));

        // Each action should have exactly one test case with one attachment
        // and we should have one that is "this is branch firstBranch" and one that is "this is branch secondBranch"
        boolean foundFirstBranch = false;
        boolean foundSecondBranch = false;
        for (TestClassAttachmentTestAction action : classActions) {
            assertThat(action.getAttachments(),
                    allOf(aMapWithSize(1),
                          hasEntry(
                                  is("someTestWithAttachments"),
                                  contains("attachment.txt"))));

            URL url = new URL(jenkinsRule.getURL(), classResult.getUrl() + "/");
            url = new URL(url, action.getUrl("someTestWithAttachments", "attachment.txt"));
            String s = fromURL(url);
            if ("this is branch firstBranch".equals(s)) {
                foundFirstBranch = true;
            } else if("this is branch secondBranch".equals(s)) {
                foundSecondBranch = true;
            }
        }
        assertTrue(foundFirstBranch, "Found first branch");
        assertTrue(foundSecondBranch, "Found second branch");
    }

    @Test
    void testClassnameWithUmlautsAndSpaces(JenkinsRule jenkinsRule) throws Exception {
        // Surefire writes @DisplayName values as the classname into the XML report.
        // When the display name contains umlauts or spaces, TestObject.safe() passes
        // them through unchanged (it only replaces / \ : ? # % < >), so those
        // characters end up as literal path components on disk. On systems where
        // sun.jnu.encoding is not UTF-8 (e.g. ASCII-only CI containers), mkdirs()
        // then throws "Malformed input or input contains unmappable characters".
        WorkflowJob project = jenkinsRule.jenkins.createProject(WorkflowJob.class, "umlaut-classname-test");
        project.setDefinition(new CpsFlowDefinition("""
            node {
                writeFile file: 'screenshot.png', text: 'fake png'
                writeFile file: 'test.xml', text: '''<?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.MyTest" time="1" tests="1" errors="0" skipped="0" failures="0">
                  <testcase name="prüfe Umlauts und Leerzeichen" classname="com.example.MyTest > Für Umlauts und Leerzeichen" time="1">
                    <system-out><![CDATA[[[ATTACHMENT|screenshot.png]]
                ]]></system-out>
                  </testcase>
                </testsuite>
                '''
                junit stdioRetention: 'ALL', testDataPublishers: [attachments()], testResults: 'test.xml'
            }
            """, true));

        WorkflowRun run = jenkinsRule.buildAndAssertSuccess(project);
        TestResultAction tra = run.getAction(TestResultAction.class);
        assertNotNull(tra);

        List<CaseResult> passedTests = tra.getPassedTests();
        assertThat(passedTests, hasSize(1));

        CaseResult caseResult = passedTests.get(0);
        List<TestCaseAttachmentTestAction> attachmentActions = caseResult.getTestActions().stream()
                .filter(TestCaseAttachmentTestAction.class::isInstance)
                .map(TestCaseAttachmentTestAction.class::cast)
                .collect(Collectors.toList());
        assertThat(attachmentActions, hasSize(1));
        assertThat(attachmentActions.get(0).getAttachments(), contains("screenshot.png"));
    }

    private static WorkflowRun buildParallelBranchesProject(JenkinsRule jenkinsRule) throws Exception {
        WorkflowJob project = jenkinsRule.jenkins.createProject(WorkflowJob.class, "tests-in-branches");
        project.setDefinition(new CpsFlowDefinition("""
            def simulateTest(String folder) {
                dir(folder) {
                    writeFile file: 'attachment.txt', text: "this is branch $folder"
                    writeFile file: 'test.xml', text: '''<?xml version="1.0" encoding="UTF-8"?>
                    <testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd" version="3.0.2" name="com.example.MyTest" time="1" tests="1" errors="0" skipped="0" failures="0">
                      <testcase name="someTestWithAttachments" classname="com.example.MyTest" time="1">
                        <system-out><![CDATA[This is some system.out data from branch %%BRANCH%%
                    ]]></system-out>
                        <system-err><![CDATA[This is some system.err.data with an attachment
                        [[ATTACHMENT|attachment.txt]]
                    ]]></system-err>
                      </testcase>
                    </testsuite>
                            '''.replace("%%BRANCH%%", folder)
                    junit stdioRetention: 'ALL', testDataPublishers: [attachments()], testResults: 'test.xml'
                }
            }
            node {
                parallel firstBranch: {
                    simulateTest("firstBranch")
                }, secondBranch: {
                    simulateTest("secondBranch")
                },
                failFast: false
            }
                """, true));
        return jenkinsRule.buildAndAssertSuccess(project);
    }

    /**
     * Obtain the string content of a single attachment from a single test.
     * @param cr the Single test to obtain the attachment for.
     * @return String content of the attachment for the test
     * @throws IOException if we could not obtain the test data (using HTTP to download)
     */
    private static String getTestAttachmentAsText(JenkinsRule jenkinsRule, CaseResult cr) throws IOException {
        List<TestCaseAttachmentTestAction> testCaseAttachmentTestActions = cr.getTestActions().stream()
                .filter(TestCaseAttachmentTestAction.class::isInstance)
                .map(TestCaseAttachmentTestAction.class::cast)
                .collect(Collectors.toList());
        assertThat(testCaseAttachmentTestActions, hasSize(1));

        TestCaseAttachmentTestAction testAttachmentAction = testCaseAttachmentTestActions.get(0);
        assertThat(testAttachmentAction.getAttachments(), hasSize(1));
        URL url = new URL(jenkinsRule.getURL(), cr.getUrl() + "/");
        url = new URL(url, TestCaseAttachmentTestAction.getUrl(testAttachmentAction.getAttachments().get(0)));
        return fromURL(url);
    }

    // Creates a job from the given workspace zip file, builds it and retrieves the TestResultAction
    private static TestResultAction getTestResultActionForPipeline(JenkinsRule jenkinsRule, String workspaceZip, String pipelineFile, Result expectedStatus) throws Exception {
        WorkflowJob project = jenkinsRule.jenkins.createProject(WorkflowJob.class, "test-job");
        FilePath workspace = jenkinsRule.jenkins.getWorkspaceFor(project);
        FilePath wsZip = workspace.child("workspace.zip");
        wsZip.copyFrom(AttachmentPublisherPipelineTest.class.getResource(workspaceZip));
        wsZip.unzip(workspace);
        for (FilePath f : workspace.list()) {
            f.touch(System.currentTimeMillis());
        }

        project.setDefinition(new CpsFlowDefinition(fileContentsFromResources(pipelineFile), true));

        WorkflowRun r = jenkinsRule.assertBuildStatus(expectedStatus, project.scheduleBuild2(0).get());

        TestResultAction action = r.getAction(TestResultAction.class);
        assertNotNull(action);

        return action;
    }

    private static String fileContentsFromResources(String fileName) throws IOException {
        String fileContents = null;

        URL url = AttachmentPublisherPipelineTest.class.getResource(fileName);
        if (url != null) {
            fileContents = fromURL(url);
        }

        return fileContents;
    }

    private static final String fromURL(URL url) throws IOException {
        try (InputStream is = url.openConnection().getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
