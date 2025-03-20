package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.Util;
import hudson.tasks.junit.ClassResult;

import java.util.List;
import java.util.Map;

public class TestClassAttachmentTestAction extends AttachmentTestAction {

    private final Map<String, List<String>> attachments;

    public TestClassAttachmentTestAction(
            ClassResult classResult,
            FilePath storage,
            Map<String, List<String>> attachments) {
        super(classResult, storage);

        this.attachments = attachments;
    }

    public Map<String, List<String>> getAttachments() {
        return attachments;
    }

    public static String getUrl(String testCase, String filename) {
        return "attachments/" + Util.rawEncode(testCase) + "/" + Util.rawEncode(filename);
    }
}
