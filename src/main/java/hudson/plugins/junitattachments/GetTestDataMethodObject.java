/**
 * Copyright 2010 Mirko Friedenhagen
 */

package hudson.plugins.junitattachments;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.tools.ant.DirectoryScanner;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestResult;

/**
 * This class is a helper for hudson.tasks.junit.TestDataPublisher.getTestData(AbstractBuild<?, ?>, Launcher,
 * BuildListener, TestResult).
 *
 * @author mfriedenhagen
 */
public class GetTestDataMethodObject {

    /** Our logger. */
    private static final Logger LOG = Logger.getLogger(GetTestDataMethodObject.class.getName());

    /** the build to inspect. */
    private final AbstractBuild<?, ?> build;

    /** the test results associated with the build. */
    private final TestResult testResult;

    /**  map of class name and list of attachments. */
    private final Map<String, List<String>> attachments;

    /**  map of class name and list of test files. */
    private final Map<String, String> reports;

    /**
     * @param build
     *            see {@link GetTestDataMethodObject#build}
     * @param testResult
     *            see {@link GetTestDataMethodObject#testResult}
     */
    public GetTestDataMethodObject(AbstractBuild<?, ?> build, @SuppressWarnings("unused") Launcher launcher,
            @SuppressWarnings("unused") BuildListener listener, TestResult testResult) {
        this.build = build;
        this.testResult = testResult;
        attachments = new HashMap<String, List<String>>();
        reports = new HashMap<String, String>();
    }

    /**
     * Returns a Map of classname vs. the stored attachments in a directory named as the test class.
     *
     * @return the map
     * @throws InterruptedException
     * @throws IOException
     * @throws IllegalStateException
     * @throws InterruptedException
     *
     */
    public Map<String, List<String>> getAttachments() throws IllegalStateException, IOException, InterruptedException {
        // build a map of className -> result xml file
        final FilePath attachmentsStorage = AttachmentPublisher.getAttachmentPath(build);
        getReports();
        LOG.fine("reports: " + reports);
        for (Map.Entry<String, String> report : reports.entrySet()) {
            final String className = report.getKey();
            final FilePath target = attachmentsStorage.child(className);
            attachFilesForReport(report, className, target);
            attachStdInAndOut(report, className, target);
        }
        return attachments;
    }

    private void attachFilesForReport(Map.Entry<String, String> report, final String className, final FilePath target)
            throws IOException, InterruptedException {
        final FilePath testDir = build.getWorkspace().child(report.getValue()).getParent().child(className);
        if (testDir.exists()) {
            target.mkdirs();
            if (testDir.copyRecursiveTo(target) > 0) {
                DirectoryScanner d = new DirectoryScanner();
                d.setBasedir(target.getRemote());
                d.scan();
                attachments.put(className, Arrays.asList(d.getIncludedFiles()));
            }
        }
    }

    /**
     * Creates a map of the all classNames and the corresponding result file.
     */
    private void getReports() {
        for (SuiteResult suiteResult : testResult.getSuites()) {
            String f = suiteResult.getFile();
            if (f != null) {
                for (String className : suiteResult.getClassNames()) {
                    reports.put(className, f);
                }
            }
        }
    }

    private void attachStdInAndOut(Map.Entry<String, String> report, String className, FilePath target)
            throws IOException, InterruptedException {
        final FilePath stdInAndOut = build.getWorkspace().child(report.getValue()).getParent().child(
                className + "-output.txt");
        LOG.fine("stdInAndOut: " + stdInAndOut.absolutize());
        if (stdInAndOut.exists()) {
            target.mkdirs();
            final FilePath stdInAndOutTarget = new FilePath(target, stdInAndOut.getName());
            stdInAndOut.copyTo(stdInAndOutTarget);
            if (attachments.containsKey(className)) {
                final List<String> list = new ArrayList<String>(attachments.get(className));
                list.add(stdInAndOutTarget.getName());
                attachments.put(className, list);
            } else {
                attachments.put(className, Arrays.asList(stdInAndOutTarget.getName()));
            }
        }
    }

}
