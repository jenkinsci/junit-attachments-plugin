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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * Map from class names to a list of attachment path names on the controller.
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
    public GetTestDataMethodObject(Run<?, ?> build, @NonNull FilePath workspace,
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
     * @throws IOException
     * @throws IllegalStateException
     * @throws InterruptedException
     *
     */
    public Map<String, Map<String, List<String>>> getAttachments(
            boolean maintainAttachmentsDirectoryStructure) throws IllegalStateException, IOException, InterruptedException {
        // build a map of className -> result xml file
        Map<String, String> reports = getReports(maintainAttachmentsDirectoryStructure);
        LOG.fine("reports: " + reports);
        for (Map.Entry<String, String> report : reports.entrySet()) {
            final String className = report.getKey();
            final FilePath reportFile = workspace.child(report.getValue());
            final FilePath target = AttachmentPublisher.getAttachmentPath(attachmentsStorage, className, null);
            attachFilesForReport(className, reportFile, target);
            attachStdInAndOut(className, reportFile, maintainAttachmentsDirectoryStructure);
        }
        return attachments;
    }

    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "TODO needs triage")
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
                Map<String, List<String>> tests = attachments.getOrDefault(className, new HashMap<String, List<String>>());
                tests.put("", new ArrayList<String>(Arrays.asList(d.getIncludedFiles())));
                attachments.put(className, tests);
            }
        }
    }

    /**
     * Creates a map of the all classNames to their corresponding result file.
     */
    private Map<String,String> getReports(boolean maintainAttachmentsDirectoryStructure)
            throws IOException, InterruptedException {

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
                var testClassName = cr.getClassName();
                var testCaseName = cr.getName();
                captureAttachments(
                    testClassName,
                    testCaseName,
                    findAttachmentsInOutput(testClassName, caseStdout + "\n" + caseStderr),
                    maintainAttachmentsDirectoryStructure);
            }

            // Capture stdout and stderr for the testsuite as a whole, if they exist
            var suiteName = suiteResult.getName();

            captureAttachments(
                suiteName,
                findAttachmentsInOutput(suiteName, suiteStdout),
                maintainAttachmentsDirectoryStructure);

            captureAttachments(
                suiteName,
                findAttachmentsInOutput(suiteName, suiteStderr),
                maintainAttachmentsDirectoryStructure);
        }
        return reports;
    }

    /**
     * Finds attachments from a test's stdout/stderr, i.e. instances of:
     * <pre>[[ATTACHMENT|/path/to/attached-file.xyz|...reserved...]]</pre>
     */
    private HashSet<FilePath> findAttachmentsInOutput(String className, String output) throws IOException, InterruptedException {

        var outputAttachments = new LinkedHashSet<FilePath>();

        if (Util.fixEmpty(output) == null) {
            return outputAttachments;
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
            FilePath src = workspace.child(fileName); // even though we use child(), this should be absolute
            if (src.isDirectory()) {
                listener.getLogger().println("Attachment " + fileName + " was referenced from the test '" + className + "' but it is a directory, not a file. Skipping.");
            } else if (src.exists()) {
                outputAttachments.add(src);
            } else {
                listener.getLogger().println("Attachment "+fileName+" was referenced from the test '"+className+"' but it doesn't exist. Skipping.");
            }
        }

        return outputAttachments;
    }

    private static final String PREFIX = "[[ATTACHMENT|";
    private static final String SUFFIX = "]]";
    private static final Pattern ATTACHMENT_PATTERN = Pattern.compile("\\[\\[ATTACHMENT\\|.+\\]\\]");

    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "TODO needs triage")
    private void attachStdInAndOut(String className, FilePath reportFile, boolean maintainAttachmentsDirectoryStructure)
            throws IOException, InterruptedException {
        final FilePath stdInAndOut = reportFile.getParent().child(className + "-output.txt");
        LOG.fine("stdInAndOut: " + stdInAndOut.absolutize());
        if (stdInAndOut.exists()) {
            captureAttachments(
                    className,
                    new HashSet<FilePath>(List.of(stdInAndOut)),
                    maintainAttachmentsDirectoryStructure);
        }
    }

    /**
     * Captures a single file as an attachment by copying it and recording it.
     *
     * @param filePaths
     *      File on the build workspace to be copied back to the controller and captured.
     */
    private void captureAttachments(
            String className,
            Set<FilePath> filePaths,
            boolean maintainAttachmentsDirectoryStructure) throws IOException, InterruptedException {
        captureAttachments(className, null, filePaths, maintainAttachmentsDirectoryStructure);
    }

    private void captureAttachments(
            String className,
            String testName,
            Set<FilePath> filePaths,
            boolean maintainAttachmentsDirectoryStructure) throws IOException, InterruptedException {

        if (filePaths == null || filePaths.isEmpty()) {
            return;
        }

        Map<String, List<String>> tests = attachments.computeIfAbsent(className, k -> new HashMap<String, List<String>>());
        List<String> testFiles = tests.computeIfAbsent(Util.fixNull(testName), k -> new ArrayList<String>());

        var baseDirectory = maintainAttachmentsDirectoryStructure ?
                getCommonBaseDirectory(filePaths) :
                null;

        FilePath target = AttachmentPublisher.getAttachmentPath(attachmentsStorage, className, testName);
        target.mkdirs();

        for (FilePath filePath : filePaths) {
            String relativeFilePath = maintainAttachmentsDirectoryStructure ?
                    getRelativePath(baseDirectory, filePath) :
                    filePath.getName();

            if (!testFiles.contains(relativeFilePath)) {
                // Only need to copy the file if it hasn't already been handled for this test case
                FilePath destinationPath = new FilePath(target, relativeFilePath);
                filePath.copyTo(destinationPath);
                testFiles.add(relativeFilePath);
            }
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

    private static String getRelativePath(FilePath base, FilePath target) {
        Path basePath = Paths.get(base.getRemote()).toAbsolutePath().normalize();
        Path targetPath = Paths.get(target.getRemote()).toAbsolutePath().normalize();

        return basePath.relativize(targetPath).toString();
    }

    private static FilePath getCommonBaseDirectory(Set<FilePath> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return null;
        }

        Iterator<FilePath> iterator = filePaths.iterator();

        Path commonBase = Paths.get(iterator.next().getRemote()).toAbsolutePath().normalize();
        if (filePaths.size() == 1) {
            return new FilePath(commonBase.getParent().toFile());
        }

        while (iterator.hasNext()) {
            Path current = Paths.get(iterator.next().getRemote()).toAbsolutePath().normalize();
            commonBase = commonPrefix(commonBase, current);

            if (commonBase == null) {
                break;
            }
        }

        return commonBase == null ? null : new FilePath(commonBase.toFile());
    }

    private static Path commonPrefix(Path p1, Path p2) {
        int minCount = Math.min(p1.getNameCount(), p2.getNameCount());
        int i = 0;
        while (i < minCount && p1.getName(i).equals(p2.getName(i))) {
            i++;
        }
        return i == 0 ? null : p1.getRoot().resolve(p1.subpath(0, i));
    }

}
