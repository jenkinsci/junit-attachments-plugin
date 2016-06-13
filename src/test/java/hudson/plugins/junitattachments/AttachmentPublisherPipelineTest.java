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
import hudson.tasks.junit.ClassResult;
import hudson.tasks.junit.TestResultAction;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import static hudson.plugins.junitattachments.AttachmentPublisherTest.getClassResult;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class AttachmentPublisherPipelineTest {
    @ClassRule
    public static JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void testWellKnownFilenamesAreAttached() throws Exception {
        TestResultAction action = getTestResultActionForPipeline("workspace.zip", "pipelineTest.groovy", Result.SUCCESS);

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

    // Creates a job from the given workspace zip file, builds it and retrieves the TestResultAction
    private TestResultAction getTestResultActionForPipeline(String workspaceZip, String pipelineFile, Result expectedStatus) throws Exception {
        WorkflowJob project = jenkinsRule.jenkins.createProject(WorkflowJob.class, "test-job");
        FilePath workspace = jenkinsRule.jenkins.getWorkspaceFor(project);
        FilePath wsZip = workspace.child("workspace.zip");
        wsZip.copyFrom(getClass().getResource(workspaceZip));
        wsZip.unzip(workspace);
        project.setDefinition(new CpsFlowDefinition(fileContentsFromResources(pipelineFile)));

        WorkflowRun r = project.scheduleBuild2(0).waitForStart();

        jenkinsRule.assertBuildStatus(expectedStatus, jenkinsRule.waitForCompletion(r));

        TestResultAction action = r.getAction(TestResultAction.class);
        assertNotNull(action);

        return action;
    }

    private String fileContentsFromResources(String fileName) throws IOException {
        String fileContents = null;

        URL url = getClass().getResource(fileName);
        if (url != null) {
            fileContents = IOUtils.toString(url);
        }

        return fileContents;

    }

}
