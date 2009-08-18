package hudson.plugins.junitattachments;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestAction;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestObject;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tools.ant.DirectoryScanner;
import org.kohsuke.stapler.DataBoundConstructor;

public class AttachmentPublisher extends TestDataPublisher {

	@DataBoundConstructor
	public AttachmentPublisher() {
	}

	public static FilePath getAttachmentPath(AbstractBuild<?, ?> build) {
		// return new FilePath(Hudson.MasterComputer.localChannel,
		// build.getRootDir().getAbsolutePath()).child("junit-attachments");
		return new FilePath(new File(build.getRootDir().getAbsolutePath()))
				.child("junit-attachments");
	}

	@Override
	public Data getTestData(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener, TestResult testResult) throws IOException,
			InterruptedException {

		// build a map of className -> result xml file
		final Map<String, String> reports = new HashMap<String, String>();
		for (SuiteResult suiteResult : testResult.getSuites()) {
			String f = suiteResult.getFile();
			if (f != null) {
				for (String className : suiteResult.getClassNames()) {
					reports.put(className, f);
				}
			}
		}

		final FilePath attachmentsStorage = getAttachmentPath(build);

		Map<String, List<String>> attachments = new HashMap<String, List<String>>();

		for (Map.Entry<String, String> report : reports.entrySet()) {
			String className = report.getKey();
			FilePath testDir = build.getWorkspace().child(report.getValue())
					.getParent().child(className);
			FilePath target = attachmentsStorage.child(className);
			if (testDir.exists()) {
				target.mkdirs();
				if (testDir.copyRecursiveTo(target) > 0) {
					DirectoryScanner d = new DirectoryScanner();
					d.setBasedir(target.getRemote());
					d.scan();
					attachments.put(className, Arrays.asList(d
							.getIncludedFiles()));
				}
			}
		}

		if (attachments.isEmpty()) {
			return null;
		}

		return new Data(attachments);

	}

	public static class Data extends TestResultAction.Data {

		private final Map<String, List<String>> attachments;

		public Data(Map<String, List<String>> attachments) {
			this.attachments = attachments;
		}

		@Override
		public List<TestAction> getTestAction(TestObject testObject) {
			ClassResult cr;
			if (testObject instanceof ClassResult) {
				cr = (ClassResult) testObject;
			} else if (testObject instanceof CaseResult) {
				cr = (ClassResult) testObject.getParent();
			} else {
				return Collections.emptyList();
			}

			String className = cr.getParent().getName() + "." + cr.getName();
			List<String> attachments = this.attachments.get(className);
			if (attachments != null) {
				return Collections
						.<TestAction> singletonList(new AttachmentTestAction(
								cr, getAttachmentPath(testObject.getOwner())
										.child(className), attachments));
			} else {
				return Collections.emptyList();
			}

		}
	}

	@Extension
	public static class DescriptorImpl extends Descriptor<TestDataPublisher> {

		@Override
		public String getDisplayName() {
			return "Publish test attachments";
		}

	}

}
