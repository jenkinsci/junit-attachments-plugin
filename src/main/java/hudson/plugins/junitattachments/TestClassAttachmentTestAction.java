package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.Util;
import hudson.tasks.junit.ClassResult;

import java.util.List;
import java.util.Map;

public class TestClassAttachmentTestAction extends AttachmentTestAction {

    private final Map<String, List<String>> attachments;
    private final boolean attachmentsStoredAtClassLevel;

    public TestClassAttachmentTestAction(
            ClassResult classResult,
            FilePath storage,
            Map<String, List<String>> attachments,
            boolean attachmentsStoredAtClassLevel) {
        super(classResult, storage);

        this.attachments = attachments;
        this.attachmentsStoredAtClassLevel = attachmentsStoredAtClassLevel;
    }

    public Map<String, List<String>> getAttachments() {
        return attachments;
    }

    public String getUrl(String testCase, String filename) {
        if (this.attachmentsStoredAtClassLevel) {
            return "attachments/" + Util.rawEncode(filename);
        }

        return "attachments/" + Util.rawEncode(testCase) + "/" + Util.rawEncode(filename);
    }
}
