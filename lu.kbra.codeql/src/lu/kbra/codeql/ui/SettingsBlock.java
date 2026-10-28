package lu.kbra.codeql.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import lu.kbra.codeql.settings.Settings;

final class SettingsBlock {
	private record Entry(Control value, Button override, Button browse) {
	}

	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final IProject project;

	SettingsBlock(final Composite parent, final IProject project) {
		this.project = project;
		final Composite grid = new Composite(parent, SWT.NONE);
		grid.setLayout(new GridLayout(4, false));
		grid.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
		this.add(grid, Settings.CLI, "CodeQL executable", null, true);
		this.add(grid, Settings.CONFIG, "CodeQL YAML config", null, true);
		this.add(grid, Settings.LANGUAGE, "CodeQL language", null, false);
		this.add(grid, Settings.BUILD_MODE, "Java build mode", new String[] { "none", "autobuild" }, false);
		this.add(grid, Settings.HIGH, "High / Critical", new String[] { "error", "warning", "info" }, false);
		this.add(grid, Settings.MEDIUM, "Medium", new String[] { "error", "warning", "info" }, false);
		this.add(grid, Settings.LOW, "Low", new String[] { "error", "warning", "info" }, false);
		final Label info = new Label(grid, SWT.WRAP);
		info.setText(project == null ? "Workspace defaults. Config paths may be absolute or relative to the workspace directory."
				: "Unchecked fields inherit individually from the nearest Maven aggregator/parent project, then the workspace. "
						+ "Relative config paths are resolved where the value was defined.");
		final GridData hint = new GridData(SWT.FILL, SWT.TOP, true, false, 4, 1);
		hint.widthHint = 620;
		info.setLayoutData(hint);
	}

	private void add(final Composite grid, final String key, final String label, final String[] values, final boolean filePicker) {
		new Label(grid, SWT.NONE).setText(label);
		Button override = null;
		if (this.project != null) {
			override = new Button(grid, SWT.CHECK);
			override.setText("Override");
		} else {
			new Label(grid, SWT.NONE);
		}
		Control field;
		if (values == null) {
			field = new Text(grid, SWT.BORDER);
		} else {
			final Combo combo = new Combo(grid, SWT.DROP_DOWN | SWT.READ_ONLY);
			combo.setItems(values);
			field = combo;
		}
		field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		Button browse = null;
		if (filePicker) {
			browse = new Button(grid, SWT.PUSH);
			browse.setText("Browse...");
			final Control edit = field;
			browse.addListener(SWT.Selection, event -> {
				final FileDialog picker = new FileDialog(grid.getShell(), SWT.OPEN);
				picker.setText(Settings.CLI.equals(key) ? "Select CodeQL executable" : "Select CodeQL YAML file");
				if (Settings.CONFIG.equals(key)) {
					picker.setFilterExtensions("*.yml;*.yaml", "*.*");
				}
				final String path = picker.open();
				if (path != null) {
					((Text) edit).setText(path);
				}
			});
		} else {
			new Label(grid, SWT.NONE);
		}

		final Entry entry = new Entry(field, override, browse);
		this.entries.put(key, entry);
		final String initial = this.project == null ? Settings.workspaceNode().get(key, Settings.fallback(key))
				: Settings.resolve(this.project, key).text();
		SettingsBlock.setText(field, initial);
		if (override != null) {
			final Button checkbox = override;
			final IEclipsePreferences node = Settings.projectNode(this.project);
			checkbox.setSelection(node.get(key, null) != null);
			checkbox.addListener(SWT.Selection, event -> {
				if (!checkbox.getSelection()) {
					SettingsBlock.setText(field, Settings.inherited(this.project, key).text());
				}
				SettingsBlock.setEnabled(entry);
			});
		}
		SettingsBlock.setEnabled(entry);
	}

	private static void setEnabled(final Entry entry) {
		final boolean enabled = entry.override == null || entry.override.getSelection();
		entry.value.setEnabled(enabled);
		if (entry.browse != null) {
			entry.browse.setEnabled(enabled);
		}
	}

	private static void setText(final Control control, final String text) {
		if (control instanceof final Text field) {
			field.setText(text);
		} else if (control instanceof final Combo field) {
			field.setText(text);
		}
	}

	private static String text(final Control control) {
		return control instanceof final Text field ? field.getText() : ((Combo) control).getText();
	}

	void defaults() {
		this.entries.forEach((key, entry) -> {
			if (entry.override != null) {
				entry.override.setSelection(false);
			}
			SettingsBlock.setText(entry.value,
					this.project == null ? Settings.fallback(key) : Settings.inherited(this.project, key).text());
			SettingsBlock.setEnabled(entry);
		});
	}

	void save() throws org.osgi.service.prefs.BackingStoreException {
		final IEclipsePreferences node = this.project == null ? Settings.workspaceNode() : Settings.projectNode(this.project);
		this.entries.forEach((key, entry) -> {
			if (entry.override != null && !entry.override.getSelection()) {
				node.remove(key);
			} else {
				node.put(key, SettingsBlock.text(entry.value).trim());
			}
		});
		node.flush();
	}
}
