package lu.kbra.codeql.scan;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;

import lu.kbra.codeql.CodeQlPlugin;
import lu.kbra.codeql.sarif.SarifReader;
import lu.kbra.codeql.settings.Settings;

/** Owns only the plug-in's marker subtype; never changes JDT/compiler markers. */
public final class MarkerStore {
	private MarkerStore() {
	}

	private static Path cache(final IProject project) throws Exception {
		final Path state = CodeQlPlugin.get().getStateLocation().toFile().toPath();
		final Path directory = state.resolve("sarif-cache");
		Files.createDirectories(directory);
		// Eclipse project names cannot include directory separators; hexadecimal escaping is unambiguous.
		final StringBuilder safeName = new StringBuilder();
		for (final byte b : project.getName().getBytes(StandardCharsets.UTF_8)) {
			safeName.append(String.format("%02x", b & 0xff));
		}
		return directory.resolve(safeName + ".sarif");
	}

	public static void remember(final IProject project, final Path sarif) throws Exception {
		final Path destination = MarkerStore.cache(project);
		final Path temp = Files.createTempFile(destination.getParent(), "sarif-", ".tmp");
		try {
			Files.copy(sarif, temp, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (final java.nio.file.AtomicMoveNotSupportedException ignored) {
				Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	public static void redraw(final IProject project) throws Exception {
		if (!project.isAccessible()) {
			return;
		}
		if (!Settings.visible()) {
			MarkerStore.clear(project);
			return;
		}
		final Path cached = MarkerStore.cache(project);
		if (Files.exists(cached)) {
			final List<SarifReader.Finding> findings = SarifReader.read(Files.readString(cached));
			MarkerStore.apply(project, findings, file -> true);
		}
	}

	public static void redrawAll() throws Exception {
		for (final IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (project.isAccessible()) {
				MarkerStore.redraw(project);
			}
		}
	}

	public static void clear(final IProject project) throws CoreException {
		project.deleteMarkers(CodeQlPlugin.MARKER, true, IResource.DEPTH_INFINITE);
	}

	public static void clearAll() throws CoreException {
		for (final IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (project.isAccessible()) {
				MarkerStore.clear(project);
			}
		}
	}

	public static void apply(final IProject project, final List<SarifReader.Finding> findings, final Predicate<IFile> selected) throws CoreException {
		// Only delete in the selected scope. A single-file rescan keeps other files' markers intact.
		for (final IMarker marker : project.findMarkers(CodeQlPlugin.MARKER, true, IResource.DEPTH_INFINITE)) {
			if (marker.getResource() instanceof final IFile file && selected.test(file)) {
				marker.delete();
			}
		}
		final Map<IFile, Document> documents = new HashMap<>();
		final Map<String, String> severityLevels = Map.of(Settings.HIGH,
				Settings.resolve(project, Settings.HIGH).text(),
				Settings.MEDIUM,
				Settings.resolve(project, Settings.MEDIUM).text(),
				Settings.LOW,
				Settings.resolve(project, Settings.LOW).text());
		for (final SarifReader.Finding finding : findings) {
			final IFile file = MarkerStore.findFile(project, finding);
			if (file == null || !selected.test(file)) {
				continue;
			}
			final IMarker marker = file.createMarker(CodeQlPlugin.MARKER);
			marker.setAttribute(IMarker.MESSAGE, "[CodeQL " + finding.ruleId() + "] " + finding.message());
			marker.setAttribute(IMarker.SOURCE_ID, CodeQlPlugin.ID);
			marker.setAttribute("ruleId", finding.ruleId());
			marker.setAttribute("codeqlLevel", finding.level().name());
			final String key = switch (finding.level()) {
			case HIGH -> Settings.HIGH;
			case MEDIUM -> Settings.MEDIUM;
			case LOW -> Settings.LOW;
			};
			final String severity = severityLevels.get(key);
			marker.setAttribute(IMarker.SEVERITY, switch (severity) {
			case "error" -> IMarker.SEVERITY_ERROR;
			case "info" -> IMarker.SEVERITY_INFO;
			default -> IMarker.SEVERITY_WARNING;
			});
			marker.setAttribute(IMarker.LINE_NUMBER, finding.startLine());
			final Document document = documents.computeIfAbsent(file, MarkerStore::loadDocument);
			if (document != null) {
				try {
					final int startLine = Math.min(finding.startLine() - 1, document.getNumberOfLines() - 1);
					final int endLine = Math.min(finding.endLine() - 1, document.getNumberOfLines() - 1);
					final int start = MarkerStore.columnOffset(document, startLine, finding.startColumn());
					int end = MarkerStore.columnOffset(document, endLine, finding.endColumn());
					if (end <= start) {
						end = Math.min(document.getLength(), start + 1);
					}
					marker.setAttribute(IMarker.CHAR_START, start);
					marker.setAttribute(IMarker.CHAR_END, end);
				} catch (final BadLocationException ignored) {
					/* line-only marker still works */ }
			}
		}
	}

	private static Document loadDocument(final IFile file) {
		try (InputStream input = file.getContents()) {
			return new Document(new String(input.readAllBytes(), Charset.forName(file.getCharset())));
		} catch (final Exception ignored) {
			return null;
		}
	}

	private static int columnOffset(final Document document, final int line, final int column) throws BadLocationException {
		final int lineStart = document.getLineOffset(line);
		final int lineLength = document.getLineInformation(line).getLength();
		return lineStart + Math.min(lineLength, Math.max(0, column - 1));
	}

	private static IFile findFile(final IProject project, final SarifReader.Finding finding) {
		if (project.getLocation() == null) {
			return null;
		}
		final Path root = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
		try {
			final Path absolute = MarkerStore.toPath(root, finding.baseUri(), finding.uri());
			if (absolute == null || !absolute.startsWith(root)) {
				return null;
			}
			final String relative = root.relativize(absolute).toString().replace('\\', '/');
			final IFile file = project.getFile(relative);
			return file.exists() ? file : null;
		} catch (final Exception ignored) {
			return null;
		}
	}

	private static Path toPath(final Path root, final String base, final String uri) {
		final URI location = URI.create(uri);
		if (location.isAbsolute()) {
			if (!"file".equalsIgnoreCase(location.getScheme())) {
				return null;
			}
			return Path.of(location).toAbsolutePath().normalize();
		}
		Path basePath = root;
		if (!base.isBlank()) {
			final URI baseUri = URI.create(base);
			if (baseUri.isAbsolute() && "file".equalsIgnoreCase(baseUri.getScheme())) {
				basePath = Path.of(baseUri);
			} else if (!baseUri.isAbsolute()) {
				basePath = root.resolve(baseUri.getPath());
			}
		}
		return basePath.resolve(location.getPath()).toAbsolutePath().normalize();
	}
}
