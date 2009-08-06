package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.model.DirectoryBrowserSupport;
import hudson.tasks.junit.TestAction;
import hudson.tasks.junit.TestObject;

import java.util.List;

public class AttachmentTestAction implements TestAction {
	
	private final FilePath storage;
	private final List<String> attachments;
	private final TestObject owner;

	public AttachmentTestAction(TestObject owner, FilePath storage, List<String> attachments) {
		this.storage = storage;
		this.attachments = attachments;
		this.owner = owner;
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
	
	public List<String> getAttachments() {
		return attachments;
	}

	public TestObject getOwner() {
		return owner;
	}

	public DirectoryBrowserSupport doDynamic() {
		return new DirectoryBrowserSupport(this, storage, "Attachments", "package.gif", true);
	}


}
