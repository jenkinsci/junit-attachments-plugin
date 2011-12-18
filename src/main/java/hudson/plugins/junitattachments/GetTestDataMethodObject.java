/**
 * Copyright 2010-2011 Mirko Friedenhagen, Kohsuke Kawaguchi
 */

package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.TaskListener;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestResult;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.apache.tools.ant.DirectoryScanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * This class is a helper for hudson.tasks.junit.TestDataPublisher.getTestData(AbstractBuild<?, ?>, Launcher,
 * BuildListener, TestResult).
 *
 * @author mfriedenhagen
 * @author Kohsuke Kawaguchi
 */
public class GetTestDataMethodObject {

    /** Our logger. */
    private static final Logger LOG = Logger.getLogger(GetTestDataMethodObject.class.getName());

    /** the build to inspect. */
    private final AbstractBuild<?, ?> build;

    /** the test results associated with the build. */
    private final TestResult testResult;

    /**
     * Map from class names to a list of attachment path names on the master.
     * The path names are relative to the {@linkplain #getAttachmentStorageFor(String) class-specific attachment storage}
     */
    private final Map<String, List<String>> attachments = new HashMap<String, List<String>>();
    private final FilePath attachmentsStorage;
    private final TaskListener listener;

    /**
     * @param build
     *            see {@link GetTestDataMethodObject#build}
     * @param testResult
     *            see {@link GetTestDataMethodObject#testResult}
     */
    public GetTestDataMethodObject(AbstractBuild<?, ?> build, @SuppressWarnings("unused") Launcher launcher,
            TaskListener listener, TestResult testResult) {
        this.build = build;
        this.testResult = testResult;
        this.listener = listener;
        attachmentsStorage = AttachmentPublisher.getAttachmentPath(build);
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
        Map<String, String> reports = getReports();
        LOG.fine("reports: " + reports);
        for (Map.Entry<String, String> report : reports.entrySet()) {
            final String className = report.getKey();
            final FilePath reportFile = build.getWorkspace().child(report.getValue());
            final FilePath target = getAttachmentStorageFor(className);
            attachFilesForReport(className, reportFile, target);
            attachStdInAndOut(className, reportFile);
        }
        return attachments;
    }

    /**
     * Attachments are stored per class name directory.
     */
    private FilePath getAttachmentStorageFor(String className) {
        return attachmentsStorage.child(className);
    }

    private void attachFilesForReport(final String className, final FilePath reportFile, final FilePath target)
            throws IOException, InterruptedException {
        final FilePath testDir = reportFile.getParent().child(className);
        if (testDir.exists()) {
            target.mkdirs();
            if (testDir.copyRecursiveTo(target) > 0) {
                DirectoryScanner d = new DirectoryScanner();
                d.setBasedir(target.getRemote());
                d.scan();
                attachments.put(className, new ArrayList<String>(Arrays.asList(d.getIncludedFiles())));
            }
        }
    }

    /**
     * Creates a map of the all classNames to their corresponding result file.
     */
    private Map<String,String> getReports() throws IOException, InterruptedException {
        Map<String,String> reports = new HashMap<String, String>();
        for (SuiteResult suiteResult : testResult.getSuites()) {
            String f = suiteResult.getFile();
            if (f != null) {
                for (String className : suiteResult.getClassNames()) {
                    reports.put(className, f);
                }
            }

            for (CaseResult cr : suiteResult.getCases()) {
                findAttachmentsInOutput(cr.getClassName(), cr.getStdout() + cr.getStderr());
            }
        }
        return reports;
    }

    /**
     * Finds the attachment from stdout/stderr, which is a line that starts with
     * [[ATTACHMENT|fileName|...reserved...]]
     */
    private void findAttachmentsInOutput(String className, String output) throws IOException, InterruptedException {
        for (String line : output.split("[\r\n]+")) {
            if (line.startsWith(PREFIX) && line.endsWith(SUFFIX)) {
                // compute the file name
                line = line.substring(PREFIX.length(),line.length()-SUFFIX.length());
                int idx = line.indexOf('|');
                if (idx>=0) line = line.substring(0,idx);

                try {
                    JSONObject o = JSONObject.fromObject(line);
                    String fileName = o.optString("file");
                    if (fileName!=null) {
                        FilePath src = build.getWorkspace().child(fileName);   // even though we use child(), this should be absolute
                        if (src.exists()) {
                            captureAttachment(className,src);
                        } else {
                            listener.getLogger().println("Attachment "+fileName+" was referenced from the test '"+className+"' but it doesn't exist. Skipping.");
                        }
                    }
                } catch (JSONException e) {
                    e.printStackTrace(listener.error("Failed to parse test attachment JSON data: "+line));
                }
            }
        }
    }

    private static final String PREFIX = "[[ATTACHMENT|";
    private static final String SUFFIX = "]]";

    private void attachStdInAndOut(String className, FilePath reportFile)
            throws IOException, InterruptedException {
        final FilePath stdInAndOut = reportFile.getParent().child(
                className + "-output.txt");
        LOG.fine("stdInAndOut: " + stdInAndOut.absolutize());
        if (stdInAndOut.exists()) {
            captureAttachment(className, stdInAndOut);
        }
    }

    /**
     * Captures a single file as an attachment by copying it and recording it.
     *
     * @param src
     *      File on the build workspace to be copied back to the master and captured.
     */
    private void captureAttachment(String className, FilePath src) throws IOException, InterruptedException {
        FilePath target = getAttachmentStorageFor(className);
        target.mkdirs();
        FilePath dst = new FilePath(target, src.getName());
        src.copyTo(dst);
        addAttachment(className, dst);
    }

    private void addAttachment(String className, FilePath copiedFileOnMaster) {
        List<String> list = attachments.get(className);
        if (list==null)
            attachments.put(className,list=new ArrayList<String>());
        list.add(copiedFileOnMaster.getName());
    }

}
