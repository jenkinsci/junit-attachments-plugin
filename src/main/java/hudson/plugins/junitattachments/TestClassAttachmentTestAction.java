package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.Util;
import hudson.tasks.junit.ClassResult;

import java.util.List;
import java.util.Map;

public class TestClassAttachmentTestAction extends AttachmentTestAction {

    private final Map<String, List<String>> attachments;
    private final boolean attachmentsStoredAtClassLevel;
    private final List<String> blocks;

    public TestClassAttachmentTestAction(
            ClassResult classResult,
            FilePath storage,
            Map<String, List<String>> attachments,
            boolean attachmentsStoredAtClassLevel,
            List<String> blocks) {
        super(classResult, storage);

        this.attachments = attachments;
        this.attachmentsStoredAtClassLevel = attachmentsStoredAtClassLevel;
        this.blocks = blocks;
    }

    public Map<String, List<String>> getAttachments() {
        return attachments;
    }

    // disambiguate actions where we have blocks for classes
    @Override
    public String getUrlName() {
        if (blocks == null || blocks.isEmpty()) {
            return "attachments";
        }
        return "attachments-" + String.join("-", blocks);
    }

    public String getUrl(String testCase, String filename) {
        if (this.attachmentsStoredAtClassLevel) {
            return getUrlName() + "/" + Util.rawEncode(filename);
        }

        return getUrlName() + "/" + Util.rawEncode(testCase) + "/" + Util.rawEncode(filename);
    }
}
