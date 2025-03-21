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
import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static hudson.plugins.junitattachments.AttachmentPublisherTest.getClassResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
            fileContents = IOUtils.toString(url, StandardCharsets.UTF_8);
        }

        return fileContents;
    }

}
