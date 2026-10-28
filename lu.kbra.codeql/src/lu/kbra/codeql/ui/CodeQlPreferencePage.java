package lu.kbra.codeql.ui;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import lu.kbra.codeql.scan.ScanService;

public final class CodeQlPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
	private SettingsBlock block;

	public CodeQlPreferencePage() {
		this.setDescription("Configure CodeQL and map its severities to Eclipse problem markers.");
	}

	@Override
	public void init(final IWorkbench workbench) {
	}

	@Override
	protected Control createContents(final Composite parent) {
		final Composite content = new Composite(parent, SWT.NONE);
		content.setLayout(new org.eclipse.swt.layout.FillLayout());
		this.block = new SettingsBlock(content, null);
		return content;
	}

	@Override
	protected void performDefaults() {
		this.block.defaults();
		super.performDefaults();
	}

	@Override
	public boolean performOk() {
		try {
			this.block.save();
			ScanService.redrawAfterSettings(null);
			return true;
		} catch (final Exception exception) {
			MessageDialog.openError(this.getShell(), "CodeQL preferences", exception.getMessage());
			return false;
		}
	}
}
