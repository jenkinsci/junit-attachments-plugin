package hudson.plugins.junitattachments;

import hudson.model.DirectoryBrowserSupport;
import hudson.tasks.junit.TestAction;
import hudson.tasks.test.TestObject;
import jenkins.util.VirtualFile;

public abstract class AttachmentTestAction extends TestAction {

	final VirtualFile storage;
	final TestObject testObject;

	public AttachmentTestAction(TestObject testObject, VirtualFile storage) {
		this.storage = storage;
		this.testObject = testObject;
	}

	public String getDisplayName() {
		return "Attachments";
	}

	public String getIconFileName() {
		return "symbol-cube";
	}

	public String getUrlName() {
		return "attachments";
	}

	public DirectoryBrowserSupport doDynamic() {
		return new DirectoryBrowserSupport(this, storage, "Attachments", "symbol-cube", true);
	}

	public TestObject getTestObject() {
		return testObject;
	}

	public static boolean isImageFile(String filename) {
		return filename.matches("(?i).+\\.(gif|jpe?g|png|svg)$");
	}
}
