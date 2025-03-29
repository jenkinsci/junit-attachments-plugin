package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.Util;
import hudson.tasks.junit.CaseResult;
import jenkins.model.Jenkins;

import java.util.List;
import java.util.regex.Pattern;

public class TestCaseAttachmentTestAction extends AttachmentTestAction {

    private static final Pattern ATTACHMENT_PATTERN = Pattern.compile("\\[\\[ATTACHMENT\\|.+]]");

    private final List<String> attachments;
    private final boolean showAttachmentsInStdOut;

    public TestCaseAttachmentTestAction(
            CaseResult caseResult, FilePath storage, List<String> attachments, boolean showAttachmentsInStdOut) {
        super(caseResult, storage);

        this.attachments = attachments;
        this.showAttachmentsInStdOut = showAttachmentsInStdOut;
    }

    public List<String> getAttachments() {
        return attachments;
    }

    @Override
    public String annotate(String text) {

        if (!showAttachmentsInStdOut) {
            text = ATTACHMENT_PATTERN.matcher(text).replaceAll("").stripTrailing();
        }

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
