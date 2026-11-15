package hudson.plugins.junitattachments;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

import hudson.FilePath;
import hudson.model.Result;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.htmlunit.Page;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class GalleryPopupTest {

    private JenkinsRule j;
    private WorkflowRun build;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        this.j = j;
        WorkflowJob p = j.createProject(WorkflowJob.class, "screenshots");
        FilePath ws = j.jenkins.getWorkspaceFor(p);
        writePng(ws.child("reports/large.png"), 1920, 1080);
        writePng(ws.child("reports/small.png"), 200, 120);
        p.setDefinition(new CpsFlowDefinition(
                """
                node {
                  writeFile file: 'reports/TEST-com.example.UiTest.xml', text: '''
                    <testsuite name="com.example.UiTest" tests="2">
                      <testcase classname="com.example.UiTest" name="large" time="1">
                        <system-out>[[ATTACHMENT|large.png]]</system-out>
                      </testcase>
                      <testcase classname="com.example.UiTest" name="small" time="1">
                        <system-out>[[ATTACHMENT|small.png]]</system-out>
                      </testcase>
                    </testsuite>'''
                  junit testResults: 'reports/*.xml', testDataPublishers: [attachments()]
                }
                """,
                true));
        build = j.assertBuildStatus(Result.SUCCESS, p.scheduleBuild2(0));
    }

    // HtmlUnit drops CSS min() values, so the image's scaled width can only be checked in a real browser.
    @Issue("https://github.com/jenkinsci/junit-attachments-plugin/issues/222")
    @Test
    void imageOpensInAWiderDialogTitledWithTheFileName() throws Exception {
        HtmlPage page = openClassPage();

        page.getAnchorByText("large.png").click();
        DomElement dialog = waitForDialog(page);

        assertThat(dialog.getAttribute("style"), containsString("max-width: 90vw"));
        assertThat(dialog.querySelector(".jenkins-dialog__title span").getTextContent(), is("large.png"));
        assertThat(((DomElement) dialog.querySelector("img")).getAttribute("style"), containsString("max-width: 100%"));
    }

    @Test
    void plainClickStillOpensThePopupInsteadOfFollowingTheLink() throws Exception {
        HtmlPage page = openClassPage();

        Page result = page.getAnchorByText("small.png").click();
        waitForDialog(page);

        assertThat(result, sameInstance(page));
        assertThat(page.getWebClient().getCurrentWindow().getEnclosedPage(), sameInstance(page));
    }

    @Issue("https://github.com/jenkinsci/junit-attachments-plugin/issues/222")
    @Test
    void modifiedClicksFollowTheLinkInsteadOfOpeningThePopup() throws Exception {
        HtmlPage page = openClassPage();
        HtmlAnchor link = page.getAnchorByText("large.png");

        link.click(false, true, false); // Ctrl-click
        link.click(true, false, false); // Shift-click
        page.getWebClient().waitForBackgroundJavaScript(2000);

        assertThat(dialogs(page), empty());
    }

    private HtmlPage openClassPage() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setDownloadImages(true);
        return wc.getPage(build, "testReport/com.example/UiTest/");
    }

    private static DomElement waitForDialog(HtmlPage page) {
        page.getWebClient().waitForBackgroundJavaScript(2000);
        List<DomElement> dialogs = dialogs(page);
        assertThat(dialogs, hasSize(1));
        return dialogs.get(0);
    }

    private static List<DomElement> dialogs(HtmlPage page) {
        return page.querySelectorAll("dialog.jenkins-dialog").stream()
                .map(DomElement.class::cast)
                .toList();
    }

    private static void writePng(FilePath file, int width, int height) throws Exception {
        file.getParent().mkdirs();
        try (OutputStream out = file.write()) {
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        }
    }
}
