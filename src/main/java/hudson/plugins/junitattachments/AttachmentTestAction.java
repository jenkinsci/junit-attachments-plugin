package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.model.DirectoryBrowserSupport;
import hudson.tasks.junit.TestAction;
import hudson.tasks.test.TestObject;
import jenkins.model.Jenkins;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

public class AttachmentTestAction extends TestAction {

	private final FilePath storage;
	private final List<String> attachments;
	private final TestObject testObject;

	public AttachmentTestAction(TestObject testObject, FilePath storage, List<String> attachments) {
		this.storage = storage;
		this.testObject = testObject;
		this.attachments = attachments;
	}

	public String getDisplayName() {
		return "Attachments";
	}

	public String getIconFileName() {
		return "package.gif";
	}

	public String getUrlName() {
		return "attachments";
	}

	public DirectoryBrowserSupport doDynamic() {
		return new DirectoryBrowserSupport(this, storage, "Attachments", "package.gif", true);
	}

	@Override
	public String annotate(String text) {
		String url = Jenkins.getActiveInstance().getRootUrl()
				+ testObject.getRun().getUrl() + "testReport"
				+ testObject.getUrl() + "/attachments/";
		for (String attachment : attachments) {
			text = text.replace(attachment, "<a href=\"" + url + attachment
					+ "\">" + attachment + "</a>");
		}
		return text;
	}

	public List<String> getAttachments() {
		return attachments;
	}

	public TestObject getTestObject() {
		return testObject;
	}

	public static boolean isImageFile(String filename) {
		return filename.matches("(?i).+\\.(gif|jpe?g|png)$");
	}

	public static String getUrl(String filename) throws UnsupportedEncodingException {
		return "attachments/" + URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
	}

}
