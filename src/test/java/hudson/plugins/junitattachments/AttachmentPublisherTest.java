package hudson.plugins.junitattachments;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.tasks.Builder;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResultAction;
import hudson.util.DescribableList;

import org.jvnet.hudson.test.ExtractResourceSCM;
import org.jvnet.hudson.test.HudsonTestCase;

public class AttachmentPublisherTest extends HudsonTestCase {

    public void test1() throws Exception {
        FreeStyleProject project = createFreeStyleProject();
        DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>> publishers = new DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>>(
                project);
        publishers.add(new AttachmentPublisher());
        JUnitResultArchiver archiver = new JUnitResultArchiver("*.xml",
                publishers);
        project.getPublishersList().add(archiver);
        project.setScm(new ExtractResourceSCM(getClass().getResource(
                "workspace.zip")));
        project.getBuildersList().add(new TouchBuilder());

        FreeStyleBuild b = project.scheduleBuild2(0).get();

        assertBuildStatusSuccess(b);

        TestResultAction action = b.getAction(TestResultAction.class);

        assertNotNull(action);

        ClassResult cr = action.getResult().byPackage("test.foo.bar")
                .getClassResult("DefaultIntegrationTest");

        AttachmentTestAction ata = cr.getTestAction(AttachmentTestAction.class);

        assertNotNull(ata);

        final List<String> attachments = ata.getAttachments();
        Collections.sort(attachments);

        assertEquals(2, attachments.size());

        assertEquals("file", attachments.get(0));
        assertEquals("test.foo.bar.DefaultIntegrationTest-output.txt", attachments.get(1));
    }

    public static final class TouchBuilder extends Builder implements
            Serializable {
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
