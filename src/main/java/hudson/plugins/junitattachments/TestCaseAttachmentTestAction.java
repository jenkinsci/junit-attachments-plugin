package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.Util;
import hudson.tasks.junit.CaseResult;
import jenkins.model.Jenkins;
import jenkins.util.VirtualFile;

import java.util.List;
import java.util.regex.Pattern;

public class TestCaseAttachmentTestAction extends AttachmentTestAction {

    private static final Pattern ATTACHMENT_PATTERN = Pattern.compile("\\[\\[ATTACHMENT\\|.+]]");

    private final List<String> attachments;
    private final boolean showAttachmentsInStdOut;

    public TestCaseAttachmentTestAction(
            CaseResult caseResult, VirtualFile storage, List<String> attachments, boolean showAttachmentsInStdOut) {
        super(caseResult, storage);

        this.attachments = attachments;
        this.showAttachmentsInStdOut = showAttachmentsInStdOut;
    }

    /**
     * @deprecated use {@link #TestCaseAttachmentTestAction(CaseResult, VirtualFile, List, boolean)}
     */
    @Deprecated
    public TestCaseAttachmentTestAction(
            CaseResult caseResult, FilePath storage, List<String> attachments, boolean showAttachmentsInStdOut) {
        this(caseResult, storage.toVirtualFile(), attachments, showAttachmentsInStdOut);
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
