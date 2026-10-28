package lu.kbra.codeql.settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import lu.kbra.codeql.CodeQlPlugin;

/** Individual keys cascade: subproject > Maven aggregator/parent > workspace. */
public final class Settings {
	public static final String CLI = "cli";
	public static final String CONFIG = "config";
	public static final String LANGUAGE = "language";
	public static final String BUILD_MODE = "buildMode";
	public static final String HIGH = "high";
	public static final String MEDIUM = "medium";
	public static final String LOW = "low";
	public static final String VISIBLE = "markersVisible";
	public static final String[] KEYS = {
			Settings.CLI,
			Settings.CONFIG,
			Settings.LANGUAGE,
			Settings.BUILD_MODE,
			Settings.HIGH,
			Settings.MEDIUM,
			Settings.LOW };

	private Settings() {
	}

	public record Value(String text, IProject owner) {
	}

	public static String fallback(final String key) {
		return switch (key) {
		case CLI -> "codeql";
		case CONFIG -> "";
		case LANGUAGE -> "java";
		case BUILD_MODE -> "none";
		case HIGH -> "error";
		case MEDIUM -> "warning";
		case LOW -> "info";
		default -> "";
		};
	}

	public static IEclipsePreferences workspaceNode() {
		return InstanceScope.INSTANCE.getNode(CodeQlPlugin.ID);
	}

	public static IEclipsePreferences projectNode(final IProject project) {
		return new ProjectScope(project).getNode(CodeQlPlugin.ID);
	}

	public static Value resolve(final IProject project, final String key) {
		return Settings.resolve(project, key, true);
	}

	public static Value inherited(final IProject project, final String key) {
		return Settings.resolve(project, key, false);
	}

	private static Value resolve(final IProject project, final String key, final boolean includeSelf) {
		final Set<IProject> visited = new HashSet<>();
		IProject current = includeSelf ? project : Settings.parent(project);
		while (current != null && visited.add(current)) {
			final IEclipsePreferences node = Settings.projectNode(current);
			if (node.get(key, null) != null) {
				return new Value(node.get(key, ""), current);
			}
			current = Settings.parent(current);
		}
		return new Value(Settings.workspaceNode().get(key, Settings.fallback(key)), null);
	}

	public static boolean visible() {
		return Settings.workspaceNode().getBoolean(Settings.VISIBLE, true);
	}

	public static Path resolveConfig(final IProject project) {
		final Value setting = Settings.resolve(project, Settings.CONFIG);
		if (setting.text().isBlank()) {
			return null;
		}
		Path path = Path.of(setting.text().trim());
		if (!path.isAbsolute()) {
			final Path base = setting.owner() == null ? ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
					: setting.owner().getLocation().toFile().toPath();
			path = base.resolve(path);
		}
		return path.normalize().toAbsolutePath();
	}

	public static IProject parent(final IProject project) {
		if (project == null || !project.isAccessible() || project.getLocation() == null) {
			return null;
		}
		final Path location = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
		final Path pomPath = location.resolve("pom.xml");
		final Element pom = Settings.readPom(pomPath);
		if (pom != null) {
			final Element parent = Settings.child(pom, "parent");
			if (parent != null) {
				final Element relativeElement = Settings.child(parent, "relativePath");
				final String relative = relativeElement == null ? "../pom.xml" : relativeElement.getTextContent().trim();
				if (!relative.isEmpty()) {
					Path possible = location.resolve(relative).normalize();
					if (Files.isDirectory(possible)) {
						possible = possible.resolve("pom.xml");
					}
					final IProject matched = Settings.projectForPom(possible);
					if (matched != null && !matched.equals(project)) {
						return matched;
					}
				}
			}
		}
		// Covers aggregator <modules> projects whose pom does not declare <parent>.
		IProject best = null;
		int bestDepth = -1;
		for (final IProject candidate : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (candidate.equals(project) || !candidate.isAccessible() || candidate.getLocation() == null) {
				continue;
			}
			final Path candidateRoot = candidate.getLocation().toFile().toPath().toAbsolutePath().normalize();
			final Element candidatePom = Settings.readPom(candidateRoot.resolve("pom.xml"));
			final Element modules = Settings.child(candidatePom, "modules");
			if (modules == null) {
				continue;
			}
			for (final Element module : Settings.children(modules, "module")) {
				final Path modulePath = candidateRoot.resolve(module.getTextContent().trim()).normalize();
				if (modulePath.equals(location) && candidateRoot.getNameCount() > bestDepth) {
					best = candidate;
					bestDepth = candidateRoot.getNameCount();
				}
			}
		}
		return best;
	}

	private static IProject projectForPom(final Path pom) {
		final Path normalized = pom.toAbsolutePath().normalize();
		for (final IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (project.isAccessible() && project.getLocation() != null
					&& project.getLocation().toFile().toPath().toAbsolutePath().normalize().resolve("pom.xml").equals(normalized)) {
				return project;
			}
		}
		return null;
	}

	private static Element readPom(final Path pom) {
		if (!Files.isRegularFile(pom)) {
			return null;
		}
		try {
			final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			return factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
		} catch (final Exception exception) {
			return null;
		}
	}

	private static Element child(final Element parent, final String name) {
		if (parent == null) {
			return null;
		}
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element && name.equals(node.getLocalName() == null ? node.getNodeName() : node.getLocalName())) {
				return (Element) node;
			}
		}
		return null;
	}

	private static List<Element> children(final Element parent, final String name) {
		final List<Element> found = new ArrayList<>();
		if (parent == null) {
			return found;
		}
		final NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			final Node node = nodes.item(i);
			if (node instanceof Element && name.equals(node.getLocalName() == null ? node.getNodeName() : node.getLocalName())) {
				found.add((Element) node);
			}
		}
		return found;
	}
}
