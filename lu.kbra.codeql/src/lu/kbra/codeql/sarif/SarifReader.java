package lu.kbra.codeql.sarif;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Extracts primary locations and normalized CodeQL finding severities from SARIF. */
public final class SarifReader {
	public enum Level {
		HIGH,
		MEDIUM,
		LOW
	}

	public record Finding(
			String uri,
			String baseUri,
			String ruleId,
			String message,
			Level level,
			int startLine,
			int startColumn,
			int endLine,
			int endColumn) {
	}

	private SarifReader() {
	}

	public static List<Finding> read(final String contents) {
		final List<Finding> found = new ArrayList<>();
		final Map<String, Object> root = Json.map(Json.parse(contents));
		for (final Object runObject : Json.list(root.get("runs"))) {
			final Map<String, Object> run = Json.map(runObject);
			final Map<String, Map<String, Object>> rules = new HashMap<>();
			final List<Object> descriptors = Json.list(Json.map(Json.map(run.get("tool")).get("driver")).get("rules"));
			for (final Object ruleObject : descriptors) {
				final Map<String, Object> rule = Json.map(ruleObject);
				rules.put(Json.str(rule.get("id")), rule);
			}
			final Map<String, Object> uriBases = Json.map(run.get("originalUriBaseIds"));
			for (final Object resultObject : Json.list(run.get("results"))) {
				final Map<String, Object> result = Json.map(resultObject);
				String ruleId = Json.str(result.get("ruleId"));
				final int index = Json.integer(result.get("ruleIndex"), -1);
				if (ruleId.isEmpty() && index >= 0 && index < descriptors.size()) {
					ruleId = Json.str(Json.map(descriptors.get(index)).get("id"));
				}
				final Map<String, Object> rule = rules.getOrDefault(ruleId, Map.of());
				final List<Object> locations = Json.list(result.get("locations"));
				if (locations.isEmpty()) {
					continue;
				}
				final Map<String, Object> physical = Json.map(Json.map(locations.get(0)).get("physicalLocation"));
				final Map<String, Object> artifact = Json.map(physical.get("artifactLocation"));
				final String uri = Json.str(artifact.get("uri"));
				if (uri.isBlank()) {
					continue;
				}
				final String baseId = Json.str(artifact.get("uriBaseId"));
				final String baseUri = Json.str(Json.map(uriBases.get(baseId)).get("uri"));
				final Map<String, Object> region = Json.map(physical.get("region"));
				final int startLine = Math.max(1, Json.integer(region.get("startLine"), 1));
				final int startColumn = Math.max(1, Json.integer(region.get("startColumn"), 1));
				final int endLine = Math.max(startLine, Json.integer(region.get("endLine"), startLine));
				final int endColumn = Math.max(1, Json.integer(region.get("endColumn"), startColumn + 1));
				String message = Json.str(Json.map(result.get("message")).get("text"));
				if (message.isBlank()) {
					message = Json.str(Json.map(result.get("message")).get("markdown"));
				}
				if (message.isBlank()) {
					message = Json.str(Json.map(rule.get("shortDescription")).get("text"));
				}
				found.add(new Finding(uri,
						baseUri,
						ruleId,
						message,
						SarifReader.severity(result, rule),
						startLine,
						startColumn,
						endLine,
						endColumn));
			}
		}
		return found;
	}

	private static Level severity(final Map<String, Object> result, final Map<String, Object> rule) {
		final Map<String, Object> resultProperties = Json.map(result.get("properties"));
		final Map<String, Object> ruleProperties = Json.map(rule.get("properties"));
		String score = Json.str(resultProperties.get("security-severity"));
		if (score.isEmpty()) {
			score = Json.str(ruleProperties.get("security-severity"));
		}
		if (!score.isEmpty()) {
			try {
				final double value = Double.parseDouble(score);
				if (value >= 7.0) {
					return Level.HIGH;
				}
				if (value >= 4.0) {
					return Level.MEDIUM;
				}
				if (value > 0.0) {
					return Level.LOW;
				}
			} catch (final NumberFormatException ignored) {
				/* use problem level */ }
		}
		String level = Json.str(resultProperties.get("problem.severity"));
		if (level.isEmpty()) {
			level = Json.str(ruleProperties.get("problem.severity"));
		}
		if (level.isEmpty()) {
			level = Json.str(Json.map(resultProperties.get("problem")).get("severity"));
		}
		if (level.isEmpty()) {
			level = Json.str(Json.map(ruleProperties.get("problem")).get("severity"));
		}
		if (level.isEmpty()) {
			level = Json.str(result.get("level"));
		}
		if (level.isEmpty()) {
			level = Json.str(Json.map(rule.get("defaultConfiguration")).get("level"));
		}
		return switch (level.toLowerCase(java.util.Locale.ROOT)) {
		case "error", "high", "critical" -> Level.HIGH;
		case "note", "recommendation", "low", "none" -> Level.LOW;
		default -> Level.MEDIUM;
		};
	}
}
