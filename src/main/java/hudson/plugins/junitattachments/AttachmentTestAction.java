package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.model.DirectoryBrowserSupport;
import hudson.tasks.junit.TestAction;
import hudson.tasks.test.TestObject;

public abstract class AttachmentTestAction extends TestAction {

	final FilePath storage;
	final TestObject testObject;

	public AttachmentTestAction(TestObject testObject, FilePath storage) {
		this.storage = storage;
		this.testObject = testObject;
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

	public TestObject getTestObject() {
		return testObject;
	}

	public static boolean isImageFile(String filename) {
		return filename.matches("(?i).+\\.(gif|jpe?g|png)$");
	}
}
