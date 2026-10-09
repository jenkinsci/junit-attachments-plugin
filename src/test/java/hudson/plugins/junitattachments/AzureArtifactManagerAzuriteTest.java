package hudson.plugins.junitattachments;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.microsoft.jenkins.artifactmanager.AzureArtifactConfig;
import com.microsoft.jenkins.artifactmanager.AzureArtifactManagerFactory;
import com.microsoftopentechnologies.windowsazurestorage.helper.AzureStorageAccount;
import hudson.model.Result;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.junit.TestResultAction;
import jenkins.model.ArtifactManagerConfiguration;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves that, with the real Azure Artifact Manager plugin configured against an Azurite emulator
 * managed by Testcontainers, published attachments:
 *
 * <ul>
 *   <li>land in blob storage with the exact expected bytes (verified independently via the Azure SDK, not just via build success);</li>
 *   <li>remain downloadable through the existing JUnit attachment URLs;</li>
 *   <li>leave no attachment payload anywhere on the controller's filesystem.</li>
 * </ul>
 *
 * <p>Requires a running Docker daemon; Testcontainers starts and stops the Azurite container
 * automatically as part of the test.
 */
@Testcontainers(disabledWithoutDocker = true)
@WithJenkins
class AzureArtifactManagerAzuriteTest {

    private static final String ACCOUNT_NAME = "devstoreaccount1";

    // Azurite's well-known, publicly documented development account key.
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    private static final int BLOB_PORT = 10000;

    // testcontainers-azure's AzuriteContainer always rebuilds its command in configure(),
    // overwriting any withCommand() call, and does not yet expose a way to append extra Azurite
    // flags (e.g. --skipApiVersionCheck). Use a plain GenericContainer with an explicit command
    // instead, replicating AzuriteContainer's default host/port setup.
    // TODO: Testcontainers main adds AzuriteContainer#withCommandOptions(); once a release
    // newer than 2.0.5 ships it, switch to
    // new AzuriteContainer(...).withCommandOptions("--skipApiVersionCheck").
    @Container
    private static final GenericContainer<?> AZURITE = new GenericContainer<>(
            DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:3.34.0"))
            .withExposedPorts(BLOB_PORT)
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck")
            .waitingFor(Wait.forLogMessage(".*Azurite Blob service successfully listens.*", 1));

    private static final String PIPELINE = """
            node {
                writeFile file: 'attachment.txt', text: 'hello from azurite'
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
    void attachmentsArePublishedToAzureBlobStorageWithoutLocalPayload(JenkinsRule j) throws Exception {
        String blobEndpoint = "http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(BLOB_PORT) + "/" + ACCOUNT_NAME;
        String containerName = "junit-attachments-test-" + UUID.randomUUID();

        AzureStorageAccount credential = new AzureStorageAccount(
                CredentialsScope.GLOBAL, "azurite", "Azurite (test only)",
                ACCOUNT_NAME, ACCOUNT_KEY, blobEndpoint, null);
        SystemCredentialsProvider.getInstance().getCredentials().add(credential);
        SystemCredentialsProvider.getInstance().save();

        AzureArtifactConfig config = new AzureArtifactConfig("azurite");
        config.setContainer(containerName);
        config.setPrefix("");

        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(new AzureArtifactManagerFactory(config));

        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .credential(new StorageSharedKeyCredential(ACCOUNT_NAME, ACCOUNT_KEY))
                .endpoint(blobEndpoint)
                .buildClient();
        BlobContainerClient container = blobServiceClient.getBlobContainerClient(containerName);

        WorkflowJob project = j.jenkins.createProject(WorkflowJob.class, "azurite-attachments-test");
        project.setDefinition(new CpsFlowDefinition(PIPELINE, true));
        WorkflowRun run = j.assertBuildStatus(Result.SUCCESS, project.scheduleBuild2(0).get());

        // Independently verify, via the Azure SDK against Azurite, that exactly the expected blob
        // exists with the exact expected bytes -- not just that the build succeeded.
        List<BlobItem> matching = container.listBlobs().stream()
                .filter(b -> b.getName().endsWith("junit-attachments/com.example.MyTest/myTest/attachment.txt"))
                .collect(Collectors.toList());
        assertThat(matching, hasSize(1));
        byte[] blobBytes = container.getBlobClient(matching.get(0).getName()).downloadContent().toBytes();
        assertThat(new String(blobBytes, StandardCharsets.UTF_8), is("hello from azurite"));

        // The selected manager is really Azure's, and attachments remain downloadable through the
        // plugin's normal test action/URL (streamed or external-redirect, depending on configuration).
        TestResultAction action = run.getAction(TestResultAction.class);
        assertNotNull(action);
        ClassResult cr = action.getResult().byPackage("com.example").getClassResult("MyTest");
        CaseResult caseResult = cr.getCaseResult("myTest");
        TestCaseAttachmentTestAction ata = caseResult.getTestAction(TestCaseAttachmentTestAction.class);
        assertNotNull(ata);
        assertThat(ata.getAttachments(), contains("attachment.txt"));

        URL url = new URL(j.getURL(), caseResult.getUrl() + "/");
        url = new URL(url, TestCaseAttachmentTestAction.getUrl("attachment.txt"));
        try (var is = url.openConnection().getInputStream()) {
            assertThat(new String(is.readAllBytes(), StandardCharsets.UTF_8), is("hello from azurite"));
        }

        // No attachment payload anywhere under the build's root directory on the controller.
        File buildDir = run.getRootDir();
        assertFalse(new File(buildDir, AttachmentPublisher.ARTIFACT_NAMESPACE).exists());
        if (Files.exists(buildDir.toPath())) {
            try (Stream<java.nio.file.Path> walk = Files.walk(buildDir.toPath())) {
                List<java.nio.file.Path> strayAttachment = walk
                        .filter(p -> p.getFileName() != null && p.getFileName().toString().equals("attachment.txt"))
                        .collect(Collectors.toList());
                assertThat("no attachment payload should be written to the controller build folder", strayAttachment, hasSize(0));
            }
        }

        // Reload: the manager/data resolve afresh from Jenkins, independent of any in-memory state.
        run.reload();
        TestResultAction reloadedAction = run.getAction(TestResultAction.class);
        assertNotNull(reloadedAction);
        ClassResult reloadedClassResult = reloadedAction.getResult().byPackage("com.example").getClassResult("MyTest");
        TestCaseAttachmentTestAction reloadedAta = reloadedClassResult.getCaseResult("myTest")
                .getTestAction(TestCaseAttachmentTestAction.class);
        assertNotNull(reloadedAta);
        assertThat(reloadedAta.getAttachments(), contains("attachment.txt"));

        container.delete();
    }
}
