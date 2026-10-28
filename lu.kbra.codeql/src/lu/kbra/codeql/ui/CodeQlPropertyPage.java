package lu.kbra.codeql.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.dialogs.PropertyPage;

import lu.kbra.codeql.scan.ScanService;

public final class CodeQlPropertyPage extends PropertyPage {
	private SettingsBlock block;
	private IProject project;

	public CodeQlPropertyPage() {
		this.setDescription("Override workspace or Maven parent CodeQL settings for this project.");
	}

	@Override
	protected Control createContents(final Composite parent) {
		this.project = this.getElement().getAdapter(IProject.class);
		final Composite content = new Composite(parent, SWT.NONE);
		content.setLayout(new org.eclipse.swt.layout.FillLayout());
		if (this.project != null) {
			this.block = new SettingsBlock(content, this.project);
		}
		return content;
	}

	@Override
	protected void performDefaults() {
		if (this.block != null) {
			this.block.defaults();
		}
		super.performDefaults();
	}

	@Override
	public boolean performOk() {
		if (this.project == null || this.block == null) {
			return false;
		}
		try {
			this.block.save();
			// An inherited severity may also have changed in open Maven modules.
			ScanService.redrawAfterSettings(null);
			return true;
		} catch (final Exception exception) {
			MessageDialog.openError(this.getShell(), "CodeQL project properties", exception.getMessage());
			return false;
		}
	}
}
