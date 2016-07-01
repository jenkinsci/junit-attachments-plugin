/**
 * Copyright 2010-2011 Mirko Friedenhagen, Kohsuke Kawaguchi
 */

package hudson.plugins.junitattachments;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestResult;
import org.apache.tools.ant.DirectoryScanner;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is a helper for {@code hudson.tasks.junit.TestDataPublisher.getTestData(AbstractBuild<?, ?>, Launcher,
 * BuildListener, TestResult)}.
 *
 * @author mfriedenhagen
 * @author Kohsuke Kawaguchi
 */
public class GetTestDataMethodObject {

    /** Our logger. */
    private static final Logger LOG = Logger.getLogger(GetTestDataMethodObject.class.getName());

    /** the build to inspect. */
    private final Run<?, ?> build;

    /** the test results associated with the build. */
    private final TestResult testResult;

    /**
     * Map from class names to a list of attachment path names on the master.
     * The path names are relative to the {@linkplain #getAttachmentStorageFor(String) class-specific attachment storage}
     */
    private final Map<String, Map<String, List<String>>> attachments = new HashMap<String, Map<String, List<String>>>();
    private final FilePath attachmentsStorage;
    private final TaskListener listener;

    /**
     * The workspace to check in for attachments.
     */
    private final FilePath workspace;

    /**
     * @param build
     *            see {@link GetTestDataMethodObject#build}
     * @param testResult
     *            see {@link GetTestDataMethodObject#testResult}
     */
    @Deprecated
    public GetTestDataMethodObject(AbstractBuild<?, ?> build, @SuppressWarnings("unused") Launcher launcher,
            TaskListener listener, TestResult testResult) {
        this.build = build;
        this.testResult = testResult;
        this.listener = listener;
        attachmentsStorage = AttachmentPublisher.getAttachmentPath(build);
        workspace = build.getWorkspace();
    }

    /**
     * @param build
     *            see {@link GetTestDataMethodObject#build}
     * @param testResult
     *            see {@link GetTestDataMethodObject#testResult}
     */
    public GetTestDataMethodObject(Run<?, ?> build, @Nonnull FilePath workspace,
                                   @SuppressWarnings("unused") Launcher launcher,
                                   TaskListener listener, TestResult testResult) {
        this.build = build;
        this.testResult = testResult;
        this.listener = listener;
        attachmentsStorage = AttachmentPublisher.getAttachmentPath(build);
        this.workspace = workspace;
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
    public Map<String, Map<String, List<String>>> getAttachments() throws IllegalStateException, IOException, InterruptedException {
        // build a map of className -> result xml file
        Map<String, String> reports = getReports();
        LOG.fine("reports: " + reports);
        for (Map.Entry<String, String> report : reports.entrySet()) {
            final String className = report.getKey();
            final FilePath reportFile = workspace.child(report.getValue());
            final FilePath target = AttachmentPublisher.getAttachmentPath(attachmentsStorage, className);
            attachFilesForReport(className, reportFile, target);
            attachStdInAndOut(className, reportFile);
        }
        return attachments;
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

                // Associate any included files with the test class, rather than an individual test case
                Map<String, List<String>> tests = new HashMap<String, List<String>>();
                tests.put("", new ArrayList<String>(Arrays.asList(d.getIncludedFiles())));
                attachments.put(className, tests);
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

            // Due to the way that CaseResult.getStd(out|err) works, we need to compare each test
            // cases's output with the test suite's output to determine if its output is unique
            String suiteStdout = Util.fixNull(suiteResult.getStdout());
            String suiteStderr = Util.fixNull(suiteResult.getStderr());

            for (CaseResult cr : suiteResult.getCases()) {
                String stdout = Util.fixNull(cr.getStdout());
                String caseStdout = suiteStdout.equals(stdout) ? null : stdout;

                String stderr = Util.fixNull(cr.getStderr());
                String caseStderr = suiteStderr.equals(stderr) ? null : stderr;

                // Add a newline so that we detect attachments if stdout has no trailing newline
                // and stderr is null (as otherwise we'd try and parse "[[ATTACHMENT|foo]]null")
                findAttachmentsInOutput(cr.getClassName(), cr.getName(), caseStdout + "\n" + caseStderr);
            }

            // Capture stdout and stderr for the testsuite as a whole, if they exist
            findAttachmentsInOutput(suiteResult.getName(), null, suiteStdout);
            findAttachmentsInOutput(suiteResult.getName(), null, suiteStderr);
        }
        return reports;
    }

    /**
     * Finds attachments from a test's stdout/stderr, i.e. instances of:
     * <pre>[[ATTACHMENT|/path/to/attached-file.xyz|...reserved...]]</pre>
     */
    private void findAttachmentsInOutput(String className, String testName, String output) throws IOException, InterruptedException {
        if (Util.fixEmpty(output) == null) {
            return;
        }

        Matcher matcher = ATTACHMENT_PATTERN.matcher(output);
        while (matcher.find()) {
            String line = matcher.group().trim(); // Be more tolerant about where ATTACHMENT lines start/end
            // compute the file name
            line = line.substring(PREFIX.length(), line.length() - SUFFIX.length());
            int idx = line.indexOf('|');
            if (idx >= 0) {
                line = line.substring(0, idx);
            }

            String fileName = line;
            if (fileName != null) {
                FilePath src = workspace.child(fileName); // even though we use child(), this should be absolute
                if (src.exists()) {
                    captureAttachment(className, testName, src);
                } else {
                    listener.getLogger().println("Attachment "+fileName+" was referenced from the test '"+className+"' but it doesn't exist. Skipping.");
                }
            }
        }
    }

    private static final String PREFIX = "[[ATTACHMENT|";
    private static final String SUFFIX = "]]";
    private static final Pattern ATTACHMENT_PATTERN = Pattern.compile("\\[\\[ATTACHMENT\\|.+\\]\\]");

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
        captureAttachment(className, null, src);
    }

    private void captureAttachment(String className, String testName, FilePath src) throws IOException, InterruptedException {
        Map<String, List<String>> tests = attachments.get(className);
        if (tests == null) {
            tests = new HashMap<String, List<String>>();
            attachments.put(className, tests);
        }
        List<String> testFiles = tests.get(Util.fixNull(testName));
        if (testFiles == null) {
            testFiles = new ArrayList<String>();
            tests.put(Util.fixNull(testName), testFiles);
        }

        String filename = src.getName();
        boolean fileAlreadyCopied = containsFilename(tests, filename);
        if (!fileAlreadyCopied) {
            // Only need to copy the file if it hasn't already been handled for this test class
            FilePath target = AttachmentPublisher.getAttachmentPath(attachmentsStorage, className);
            target.mkdirs();
            FilePath dst = new FilePath(target, filename);
            src.copyTo(dst);
        }

        // Add the file to the list of attachments for this test method, if it wasn't already
        if (!testFiles.contains(filename)) {
            testFiles.add(filename);
        }
    }

    /** Determines whether the given mapping for a test class contains a certain filename. */
    private static boolean containsFilename(Map<String, List<String>> map, String filename) {
        for (List<String> list : map.values()) {
            if (list.contains(filename)) {
                return true;
            }
        }
        return false;
    }

}
