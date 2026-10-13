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
import hudson.remoting.VirtualChannel;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestResult;
import jenkins.MasterToSlaveFileCallable;
import jenkins.util.BuildListenerAdapter;
import org.apache.tools.ant.DirectoryScanner;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
     * Map from class names to a list of attachment path names.
     * The path names are relative to the {@linkplain #getAttachmentsStorage() class-specific attachment storage}
     */
    private final Map<String, Map<String, List<String>>> attachments = new HashMap<String, Map<String, List<String>>>();
    private final TaskListener listener;
    private final List<String> enclosingBlocks;

    /**
     * The workspace to check in for attachments.
     */
    private final FilePath workspace;

    /**
     * Used to archive staged attachments through the build's {@link jenkins.model.ArtifactManager}.
     * {@code null} when this instance was constructed via the deprecated constructor, in which case
     * attachments are instead written directly to the legacy, filesystem-based storage location.
     */
    private final Launcher launcher;

    /**
     * Whether attachments collected by this instance should be published through the build's
     * {@link jenkins.model.ArtifactManager} (staged on the agent and archived) rather than
     * written directly to the legacy, filesystem-based storage location.
     */
    private final boolean useArtifactManager;

    /**
     * Lazily resolved root under which attachments for this invocation are written: either a
     * directory inside an agent-side staging area (when {@link #useArtifactManager} is {@code true}),
     * or the legacy, controller-local directory otherwise. Created on first use so that no
     * staging area is created when there is nothing to attach.
     */
    private FilePath attachmentsStorage;

    /**
     * The ephemeral, agent-side directory staging attachments prior to archiving, if any has been
     * created. Always cleaned up once {@link #getAttachments()} returns.
     */
    private FilePath stagingTempDir;

    /**
     * @param build
     *            see {@link GetTestDataMethodObject#build}
     * @param testResult
     *            see {@link GetTestDataMethodObject#testResult}
     * @deprecated Predates {@link jenkins.model.ArtifactManager}-based publishing; attachments
     *      collected via this constructor are always written to the legacy, filesystem-based
     *      storage location on the controller.
     */
    @Deprecated
    public GetTestDataMethodObject(AbstractBuild<?, ?> build, @SuppressWarnings("unused") Launcher launcher,
            TaskListener listener, TestResult testResult) {
        this.build = build;
        this.testResult = testResult;
        this.listener = listener;
        this.enclosingBlocks = Collections.emptyList();
        this.workspace = build.getWorkspace();
        this.launcher = null;
        this.useArtifactManager = false;
    }

    /**
     * @param build
     *            see {@link GetTestDataMethodObject#build}
     * @param testResult
     *            see {@link GetTestDataMethodObject#testResult}
     */
    public GetTestDataMethodObject(Run<?, ?> build, @NonNull FilePath workspace,
                                   Launcher launcher,
                                   TaskListener listener, TestResult testResult) {
        this.build = build;
        this.testResult = testResult;
        this.listener = listener;
        this.workspace = workspace;
        this.launcher = launcher;
        this.useArtifactManager = true;

        List<String> blocks = Collections.emptyList();
        for (SuiteResult suite : testResult.getSuites()) {
            List<String> eb = suite.getEnclosingBlocks();
            if (eb != null && !eb.isEmpty()) {
                blocks = eb;
                break;
            }
        }
        this.enclosingBlocks = blocks;
    }

    public List<String> getEnclosingBlocks() {
        return enclosingBlocks;
    }

    /**
     * Returns a Map of classname vs. the stored attachments in a directory named as the test class.
     *
     * <p>As a side effect, any newly discovered attachments are staged on the agent and archived
     * through the build's {@link jenkins.model.ArtifactManager} (unless this instance was
     * constructed via the deprecated constructor). The staging area is always removed before this
     * method returns, regardless of success or failure.
     *
     * @return the map
     * @throws InterruptedException
     * @throws IOException
     * @throws IllegalStateException
     * @throws InterruptedException
     *
     */
    public Map<String, Map<String, List<String>>> getAttachments() throws IllegalStateException, IOException, InterruptedException {
        IOException ioFailure = null;
        InterruptedException interruptedFailure = null;
        try {
            // build a map of className -> result xml file
            Map<String, String> reports = getReports();
            LOG.fine("reports: " + reports);
            for (Map.Entry<String, String> report : reports.entrySet()) {
                final String className = report.getKey();
                final FilePath reportFile = workspace.child(report.getValue());
                attachFilesForReport(className, reportFile);
                attachStdInAndOut(className, reportFile);
            }
            archiveStagedAttachments();
        } catch (IOException e) {
            ioFailure = e;
            throw e;
        } catch (InterruptedException e) {
            interruptedFailure = e;
            throw e;
        } finally {
            try {
                cleanupStaging();
            } catch (IOException cleanupFailure) {
                if (ioFailure != null) {
                    ioFailure.addSuppressed(cleanupFailure);
                } else if (interruptedFailure != null) {
                    interruptedFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            } catch (InterruptedException cleanupFailure) {
                if (ioFailure != null) {
                    ioFailure.addSuppressed(cleanupFailure);
                } else if (interruptedFailure != null) {
                    interruptedFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
        return attachments;
    }

    /**
     * Resolves (creating on first use) the root under which attachments for this invocation are
     * written. When publishing through the {@link jenkins.model.ArtifactManager}, this creates an
     * ephemeral staging directory on the same channel as {@link #workspace}, so attachments never
     * need to cross to the controller before being archived.
     */
    private FilePath getAttachmentsStorage() throws IOException, InterruptedException {
        if (attachmentsStorage == null) {
            FilePath base;
            if (useArtifactManager) {
                stagingTempDir = workspace.createTempDir("junit-attachments-staging", null);
                base = stagingTempDir.child(AttachmentPublisher.ARTIFACT_NAMESPACE);
            } else {
                base = AttachmentPublisher.getAttachmentPath(build);
            }
            if (!enclosingBlocks.isEmpty()) {
                base = base.child(String.join("-", enclosingBlocks));
            }
            attachmentsStorage = base;
        }
        return attachmentsStorage;
    }

    /**
     * Archives everything staged under {@link #stagingTempDir} through the build's
     * {@link jenkins.model.ArtifactManager}, using an identity mapping between archive-relative
     * destination paths and their (equal) paths relative to the staging directory. A no-op if
     * nothing was staged.
     */
    private void archiveStagedAttachments() throws IOException, InterruptedException {
        if (stagingTempDir == null) {
            return;
        }
        List<String> relativePaths = stagingTempDir.act(new ListRelativeFiles());
        if (relativePaths.isEmpty()) {
            return;
        }
        Map<String, String> artifactsToArchive = new LinkedHashMap<String, String>();
        for (String relativePath : relativePaths) {
            String normalized = relativePath.replace('\\', '/');
            artifactsToArchive.put(normalized, normalized);
        }
        build.pickArtifactManager().archive(stagingTempDir, launcher, BuildListenerAdapter.wrap(listener), artifactsToArchive);
    }

    /** Removes the ephemeral staging directory, if one was created. */
    private void cleanupStaging() throws IOException, InterruptedException {
        if (stagingTempDir != null) {
            stagingTempDir.deleteRecursive();
        }
    }

    /** Recursively lists the files (not directories) under a given root, as paths relative to it. */
    private static final class ListRelativeFiles extends MasterToSlaveFileCallable<List<String>> {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public List<String> invoke(File f, VirtualChannel channel) {
            DirectoryScanner d = new DirectoryScanner();
            d.setBasedir(f);
            d.scan();
            return new ArrayList<String>(Arrays.asList(d.getIncludedFiles()));
        }
    }

    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "TODO needs triage")
    private void attachFilesForReport(final String className, final FilePath reportFile)
            throws IOException, InterruptedException {
        final FilePath testDir = reportFile.getParent().child(className);
        if (testDir.exists()) {
            final FilePath target = AttachmentPublisher.getAttachmentPath(getAttachmentsStorage(), className, null);
            target.mkdirs();
            if (testDir.copyRecursiveTo(target) > 0) {
                List<String> includedFiles = target.act(new ListRelativeFiles());

                // Associate any included files with the test class, rather than an individual test case
                Map<String, List<String>> tests = attachments.getOrDefault(className, new HashMap<String, List<String>>());
                tests.put("", new ArrayList<String>(includedFiles));
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
                if (src.isDirectory()) {
                    listener.getLogger().println("Attachment " + fileName + " was referenced from the test '" + className + "' but it is a directory, not a file. Skipping.");
                } else if (src.exists()) {
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

    @SuppressFBWarnings(value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", justification = "TODO needs triage")
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
     *      File on the build workspace to be copied back to the controller and captured.
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
        if (!testFiles.contains(filename)) {
            // Only need to copy the file if it hasn't already been handled for this test class
            FilePath target = AttachmentPublisher.getAttachmentPath(getAttachmentsStorage(), className, testName);
            target.mkdirs();
            FilePath dst = new FilePath(target, filename);
            src.copyTo(dst);
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
