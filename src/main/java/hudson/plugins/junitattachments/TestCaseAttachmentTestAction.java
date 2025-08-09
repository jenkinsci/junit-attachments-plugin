package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.Util;
import hudson.tasks.junit.CaseResult;
import jenkins.model.Jenkins;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestCaseAttachmentTestAction extends AttachmentTestAction {

    private static final Pattern ATTACHMENT_PATTERN = Pattern.compile("\\[\\[ATTACHMENT\\|.+]]");
    private static final Pattern OUTSIDE_ANCHOR_PATTERN = Pattern.compile("(?s)(?:^|</a>)(.*?)(?=<a\\b|$)", Pattern.CASE_INSENSITIVE);

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

        var attachmentsDescendingOrderByFilePathLength = attachments.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();

        for (String attachment : attachmentsDescendingOrderByFilePathLength) {
            var attachmentPattern = Pattern.compile("\\b" + Pattern.quote(attachment) + "\\b");
            var attachmentLink = "<a href=\"" + url + attachment.replace('\\', '/') + "\">" + attachment + "</a>";

            var result = new StringBuilder();
            Matcher outsideMatcher = OUTSIDE_ANCHOR_PATTERN.matcher(text);
            while (outsideMatcher.find()) {
                var fullMatch = outsideMatcher.group(0);
                var chunk = outsideMatcher.group(1);
                Matcher filenameMatcher = attachmentPattern.matcher(chunk);
                String replacedChunk = filenameMatcher.replaceAll(Matcher.quoteReplacement(attachmentLink));
                outsideMatcher.appendReplacement(result, Matcher.quoteReplacement(fullMatch.replace(chunk, replacedChunk)));
            }

            outsideMatcher.appendTail(result);
            text = result.toString();
        }

        return text;
    }

    public static String getUrl(String filename) {
        return "attachments/" + Util.rawEncode(filename.replace('\\', '/'));
    }
}
