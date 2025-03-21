package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.Util;
import hudson.tasks.junit.CaseResult;
import jenkins.model.Jenkins;

import java.util.List;

public class TestCaseAttachmentTestAction extends AttachmentTestAction {

    private final List<String> attachments;

    public TestCaseAttachmentTestAction(CaseResult caseResult, FilePath storage, List<String> attachments) {
        super(caseResult, storage);

        this.attachments = attachments;
    }

    public List<String> getAttachments() {
        return attachments;
    }

    @Override
    public String annotate(String text) {
        String url = Jenkins.get().getRootUrl() + testObject.getUrl() + "/attachments/";
        for (String attachment : attachments) {
            text = text.replace(attachment, "<a href=\"" + url + attachment
                    + "\">" + attachment + "</a>");
        }
        return text;
    }

    public static String getUrl(String filename) {
        return "attachments/" + Util.rawEncode(filename);
    }
}
