package lu.kbra.codeql.scan;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;

import lu.kbra.codeql.CodeQlPlugin;
import lu.kbra.codeql.sarif.SarifReader;
import lu.kbra.codeql.settings.Settings;

/** Runs the CLI in cancellable background jobs. CodeQL extraction is project-wide. */
public final class ScanService {
	private ScanService() {
	}

	public static void scan(final IProject project, final Predicate<IFile> scope, final String label) {
		if (project == null || !project.isAccessible()) {
			return;
		}
		final Job job = Job.create("CodeQL: " + label + " (" + project.getName() + ")", monitor -> {
			try {
				ScanService.analyze(project, scope, monitor);
				return Status.OK_STATUS;
			} catch (final OperationCanceledException exception) {
				return Status.CANCEL_STATUS;
			} catch (final Exception exception) {
				return ScanService.report(exception);
			}
		});
		job.setRule(project);
		job.setUser(true);
		job.schedule();
	}

	public static void scanAll() {
		final Job job = Job.create("CodeQL: Rescan all projects", monitor -> {
			final List<IProject> projects = new ArrayList<>();
			for (final IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
				if (ScanService.isJavaProject(project)) {
					projects.add(project);
				}
			}
			final SubMonitor progress = SubMonitor.convert(monitor, Math.max(1, projects.size()) * 100);
			final MultiStatus status = new MultiStatus(CodeQlPlugin.ID, 0, "Some CodeQL scans failed", null);
			for (int index = 0; index < projects.size(); index++) {
				final IProject project = projects.get(index);
				if (progress.isCanceled()) {
					return Status.CANCEL_STATUS;
				}
				progress.subTask("Project " + (index + 1) + "/" + projects.size() + ": " + project.getName());
				try {
					ScanService.analyze(project, file -> true, progress.split(100));
				} catch (final OperationCanceledException exception) {
					return Status.CANCEL_STATUS;
				} catch (final Exception exception) {
					status.add(new Status(IStatus.ERROR, CodeQlPlugin.ID, project.getName() + ": " + exception.getMessage(), exception));
				}
			}
			if (!status.isOK()) {
				ScanService.report(new IOException(status.getMessage() + ": " + status.getChildren().length + " project(s)"));
			}
			return status.isOK() ? Status.OK_STATUS : status;
		});
		job.setRule(ResourcesPlugin.getWorkspace().getRoot());
		job.setUser(true);
		job.schedule();
	}

	public static void toggleMarkers() {
		final Job job = Job.create("CodeQL: Hide/Show markers", monitor -> {
			try {
				final boolean show = !Settings.visible();
				Settings.workspaceNode().putBoolean(Settings.VISIBLE, show);
				Settings.workspaceNode().flush();
				if (show) {
					MarkerStore.redrawAll();
				} else {
					MarkerStore.clearAll();
				}
				return Status.OK_STATUS;
			} catch (final Exception exception) {
				return ScanService.report(exception);
			}
		});
		job.setRule(ResourcesPlugin.getWorkspace().getRoot());
		job.setUser(true);
		job.schedule();
	}

	public static void redrawAfterSettings(final IProject project) {
		final Job job = Job.create("CodeQL: Update marker severities", monitor -> {
			try {
				if (project == null) {
					MarkerStore.redrawAll();
				} else {
					MarkerStore.redraw(project);
				}
				return Status.OK_STATUS;
			} catch (final Exception exception) {
				return ScanService.report(exception);
			}
		});
		job.setRule(project == null ? ResourcesPlugin.getWorkspace().getRoot() : project);
		job.schedule();
	}

	private static boolean isJavaProject(final IProject project) {
		if (!project.isAccessible() || project.getLocation() == null) {
			return false;
		}
		try {
			return project.hasNature(JavaCore.NATURE_ID);
		} catch (final Exception ignored) {
			return project.getFile("pom.xml").exists();
		}
	}

	private static void analyze(final IProject project, final Predicate<IFile> scope, final IProgressMonitor monitor) throws Exception {
		if (project.getLocation() == null) {
			throw new IOException("Project has no local filesystem location");
		}
		final Path sourceRoot = project.getLocation().toFile().toPath().toAbsolutePath();
		final Path config = Settings.resolveConfig(project);
		if (config != null && !Files.isRegularFile(config)) {
			throw new IOException("CodeQL configuration file not found: " + config);
		}
		final String cli = Settings.resolve(project, Settings.CLI).text().trim();
		final String language = Settings.resolve(project, Settings.LANGUAGE).text().trim();
		final String buildMode = Settings.resolve(project, Settings.BUILD_MODE).text().trim();
		if (cli.isEmpty() || language.isEmpty()) {
			throw new IOException("Configure the CodeQL executable and language first");
		}
		final SubMonitor progress = SubMonitor.convert(monitor, "Analyzing " + project.getName(), 100);
		final Path work = Files.createTempDirectory(CodeQlPlugin.get().getStateLocation().toFile().toPath(), "codeql-scan-");
		try {
			final Path database = work.resolve("database");
			final Path sarif = work.resolve("results.sarif");
			final List<String> create = new ArrayList<>(List.of(cli,
					"database",
					"create",
					database.toString(),
					"--source-root=" + sourceRoot,
					"--language=" + language,
					"--build-mode=" + buildMode,
					"--verbosity=progress+"));
			if (config != null) {
				create.add("--codescanning-config=" + config);
			}
			ScanService.run(create, sourceRoot, work.resolve("create.log"), progress.split(60), "Creating database", 30);
			ScanService.run(List.of(cli,
					"database",
					"analyze",
					database.toString(),
					"--format=sarif-latest",
					"--output=" + sarif,
					"--verbosity=progress+"), sourceRoot, work.resolve("analyze.log"), progress.split(35), "Running CodeQL queries", 45);
			// Parse before replacing the previous successful cache or markers.
			progress.subTask("Importing CodeQL findings for " + project.getName());
			final List<SarifReader.Finding> findings = SarifReader.read(Files.readString(sarif, StandardCharsets.UTF_8));
			MarkerStore.remember(project, sarif);
			if (Settings.visible()) {
				MarkerStore.apply(project, findings, scope);
			}
			progress.subTask("Completed " + project.getName() + " (" + findings.size() + " findings)");
			progress.worked(5);
		} finally {
			ScanService.deleteTemporaryTree(work);
		}
	}

	private static void run(final List<String> arguments, final Path cwd, final Path log, final IProgressMonitor monitor, final String phase, final int expectedSeconds)
			throws Exception {
		final SubMonitor progress = SubMonitor.convert(monitor, phase, 100);
		if (progress.isCanceled()) {
			throw new OperationCanceledException();
		}
		final ProcessBuilder builder = new ProcessBuilder(arguments);
		builder.directory(cwd.toFile());
		builder.redirectErrorStream(true);
		builder.redirectOutput(log.toFile());
		Process process;
		try {
			process = builder.start();
		} catch (final IOException exception) {
			throw new IOException("Could not start CodeQL (check executable and PATH): " + arguments.get(0), exception);
		}
		final long started = System.nanoTime();
		final long[] logPosition = { 0L };
		String lastMessage = "";
		int displayedPercent = 0;
		try {
			while (true) {
				if (progress.isCanceled()) {
					throw new OperationCanceledException();
				}
				final boolean finished = process.waitFor(500, TimeUnit.MILLISECONDS);
				final String message = ScanService.lastLogMessage(log, logPosition);
				if (!message.isBlank()) {
					lastMessage = message;
				}
				final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
				// CodeQL does not expose a reliable whole-command completion percentage.
				// Keep an explicitly estimated, moving bar below 95% until the CLI exits.
				final int estimate = finished ? 100 : ScanService.estimatePercent(elapsedMs, expectedSeconds);
				progress.worked(estimate - displayedPercent);
				displayedPercent = estimate;
				progress.subTask(phase + (finished ? " — complete" : " — approximately " + estimate + "% (estimate)")
						+ (lastMessage.isBlank() ? "" : " | " + lastMessage));
				if (finished) {
					break;
				}
			}
			if (progress.isCanceled()) {
				throw new OperationCanceledException();
			}
			if (process.exitValue() != 0) {
				String output = Files.readString(log, StandardCharsets.UTF_8);
				if (output.length() > 3500) {
					output = output.substring(output.length() - 3500);
				}
				throw new IOException("CodeQL exited with status " + process.exitValue() + ":\n" + output);
			}
		} finally {
			if (process.isAlive()) {
				process.destroy();
				if (!process.waitFor(2, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			}
		}
	}

	/** Moves steadily without asserting that elapsed time equals files or queries scanned. */
	private static int estimatePercent(final long elapsedMs, final int expectedSeconds) {
		final long halfWay = Math.max(1, expectedSeconds) * 1_000L;
		return (int) Math.min(95L, 95L * elapsedMs / (elapsedMs + halfWay));
	}

	/** Follows CodeQL's log incrementally, avoiding repeated reads of the full log file. */
	private static String lastLogMessage(final Path path, final long[] cursor) throws IOException {
		try (RandomAccessFile input = new RandomAccessFile(path.toFile(), "r")) {
			input.seek(Math.min(cursor[0], input.length()));
			String latest = "";
			String line;
			while ((line = input.readLine()) != null) {
				final String cleaned = line.replaceAll("\\p{Cntrl}", " ").trim();
				if (!cleaned.isEmpty()) {
					latest = cleaned;
				}
			}
			cursor[0] = input.getFilePointer();
			return latest.length() > 110 ? latest.substring(0, 110) + "…" : latest;
		}
	}

	private static void deleteTemporaryTree(final Path directory) {
		try (var paths = Files.walk(directory)) {
			for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (final IOException exception) {
			CodeQlPlugin.log(exception);
		}
	}

	private static IStatus report(final Exception exception) {
		CodeQlPlugin.log(exception);
		final Display display = Display.getDefault();
		if (display != null && !display.isDisposed()) {
			display.asyncExec(() -> {
				if (!display.isDisposed() && display.getActiveShell() != null) {
					MessageDialog.openError(display.getActiveShell(), "CodeQL scan failed", exception.getMessage());
				}
			});
		}
		return new Status(IStatus.ERROR, CodeQlPlugin.ID, exception.getMessage(), exception);
	}
}
