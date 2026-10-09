package hudson.plugins.junitattachments;

import hudson.model.Result;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.junit.TestResultAction;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.util.VirtualFile;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider-neutral regression coverage asserting that attachments are published exclusively
 * through the build's {@link jenkins.model.ArtifactManager}: using the test-only
 * {@link MemoryArtifactManagerFactory} as a stand-in for a remote provider (e.g. Azure Artifact
 * Manager backed by Azurite), verifies that no attachment payload is written to the controller's
 * local build folder, that archived bytes match exactly, that provider failures are not silently
 * swallowed, and that published attachments participate in the normal artifact listing.
 */
@WithJenkins
class AttachmentArtifactManagerStorageTest {

    private static final String PIPELINE = """
            node {
                writeFile file: 'attachment.txt', text: 'hello from the attachment'
                writeFile file: 'test.xml', text: '''<?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.MyTest" time="1" tests="1" errors="0" skipped="0" failures="0">
                  <testcase name="myTest" classname="com.example.MyTest" time="1">
                    <system-out><![CDATA[[[ATTACHMENT|attachment.txt]]
                ]]></system-out>
                  </testcase>
                </testsuite>
                '''
                junit testDataPublishers: [attachments()], testResults: 'test.xml'
            }
            """;

    @Test
    void attachmentsArchivedThroughArtifactManagerWithNoLocalPayload(JenkinsRule j) throws Exception {
        MemoryArtifactManagerFactory factory = registerMemoryFactory(j);

        WorkflowRun run = runPipeline(j, "no-local-payload", PIPELINE, Result.SUCCESS);

        // Exactly one archive() call, for exactly the one attachment, with an identity mapping.
        assertThat(factory.archiveCalls, hasSize(1));
        Map<String, String> archived = factory.archiveCalls.get(0);
        assertThat(archived.keySet(), hasSize(1));
        String archivePath = archived.keySet().iterator().next();
        assertThat(archivePath, is(archived.get(archivePath))); // identity map
        assertTrue(archivePath.startsWith(AttachmentPublisher.ARTIFACT_NAMESPACE + "/"));

        // The archived bytes match exactly what was written in the workspace.
        assertThat(new String(factory.files.get(archivePath), StandardCharsets.UTF_8), is("hello from the attachment"));

        // Data is marked as stored via the ArtifactManager, and the attachment is downloadable
        // through the usual test action/URL.
        TestResultAction action = run.getAction(TestResultAction.class);
        assertNotNull(action);
        ClassResult cr = action.getResult().byPackage("com.example").getClassResult("MyTest");
        CaseResult caseResult = cr.getCaseResult("myTest");
        TestCaseAttachmentTestAction ata = caseResult.getTestAction(TestCaseAttachmentTestAction.class);
        assertNotNull(ata);
        assertThat(ata.getAttachments(), contains("attachment.txt"));

        URL url = new URL(j.getURL(), caseResult.getUrl() + "/");
        url = new URL(url, TestCaseAttachmentTestAction.getUrl("attachment.txt"));
        assertThat(fromURL(url), is("hello from the attachment"));

        // No legacy directory and no attachment payload anywhere under the build's root directory:
        // the only thing on the controller's filesystem is normal build/test metadata.
        File buildDir = run.getRootDir();
        assertFalse(new File(buildDir, AttachmentPublisher.ARTIFACT_NAMESPACE).exists());
        assertNoFileNamed(buildDir.toPath(), "attachment.txt");

        // No staging residue left behind in the workspace either.
        // FilePath#createTempDir appends a generated suffix to the prefix, so match on the prefix.
        assertNoFileNamedStartingWith(j.jenkins.getWorkspaceFor((WorkflowJob) run.getParent()).getRemote(), "junit-attachments-staging");
    }

    @Test
    void attachmentsAppearInNormalArtifactListing(JenkinsRule j) throws Exception {
        registerMemoryFactory(j);

        WorkflowRun run = runPipeline(j, "artifact-listing", PIPELINE, Result.SUCCESS);

        VirtualFile attachment = run.getArtifactManager().root()
                .child(AttachmentPublisher.ARTIFACT_NAMESPACE).child("com.example.MyTest").child("myTest").child("attachment.txt");
        assertTrue(attachment.exists());
        assertThat(new String(attachment.open().readAllBytes(), StandardCharsets.UTF_8), is("hello from the attachment"));
    }

    @Test
    void archiveFailurePropagatesWithoutLocalFallback(JenkinsRule j) throws Exception {
        MemoryArtifactManagerFactory factory = registerMemoryFactory(j);
        factory.failArchiveWith = new IOException("simulated provider failure");

        WorkflowRun run = runPipeline(j, "archive-failure", PIPELINE, Result.FAILURE);

        // The provider never actually stored anything, and the plugin did not fall back to
        // writing attachments on the controller.
        assertThat(factory.files.entrySet(), empty());
        assertFalse(new File(run.getRootDir(), AttachmentPublisher.ARTIFACT_NAMESPACE).exists());
    }

    @Test
    void legacyDataWithoutStorageFlagDefaultsToFilesystemStorage() {
        // Simulates XStream restoring historical XML that predates the storedViaArtifactManager
        // field: the deprecated constructor (as old callers/old deserialized objects would use)
        // must still resolve to the legacy, filesystem-based storage location.
        AttachmentPublisher.Data legacy = new AttachmentPublisher.Data(Map.of(), true, true, List.of());
        assertFalse(legacy.isStoredViaArtifactManager());

        AttachmentPublisher.Data current = new AttachmentPublisher.Data(Map.of(), true, true, List.of(), true);
        assertTrue(current.isStoredViaArtifactManager());
    }

    private static MemoryArtifactManagerFactory registerMemoryFactory(JenkinsRule j) throws Exception {
        MemoryArtifactManagerFactory factory = new MemoryArtifactManagerFactory();
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(factory);
        return factory;
    }

    private static WorkflowRun runPipeline(JenkinsRule j, String jobName, String pipeline, Result expectedStatus) throws Exception {
        WorkflowJob project = j.jenkins.createProject(WorkflowJob.class, jobName);
        project.setDefinition(new CpsFlowDefinition(pipeline, true));
        return j.assertBuildStatus(expectedStatus, project.scheduleBuild2(0).get());
    }

    private static void assertNoFileNamed(java.nio.file.Path root, String name) throws IOException {
        assertNoFileMatching(root, name::equals, "named " + name);
    }

    private static void assertNoFileNamedStartingWith(String rootPath, String prefix) throws IOException {
        assertNoFileMatching(new File(rootPath).toPath(), n -> n.startsWith(prefix), "starting with " + prefix);
    }

    private static void assertNoFileMatching(java.nio.file.Path root, Predicate<String> fileName, String description)
            throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<java.nio.file.Path> walk = Files.walk(root)) {
            List<java.nio.file.Path> matches = walk.filter(p -> p.getFileName() != null && fileName.test(p.getFileName().toString()))
                    .collect(Collectors.toList());
            assertThat("unexpected file/dir " + description + " under " + root + ": " + matches, matches, empty());
        }
    }

    private static String fromURL(URL url) throws IOException {
        try (var is = url.openConnection().getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
