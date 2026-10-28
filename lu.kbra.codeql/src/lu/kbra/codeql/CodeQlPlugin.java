package lu.kbra.codeql;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public final class CodeQlPlugin extends AbstractUIPlugin {
	public static final String ID = "lu.kbra.codeql";
	public static final String MARKER = CodeQlPlugin.ID + ".problem";
	private static CodeQlPlugin instance;

	@Override
	public void start(final BundleContext context) throws Exception {
		super.start(context);
		CodeQlPlugin.instance = this;
	}

	@Override
	public void stop(final BundleContext context) throws Exception {
		CodeQlPlugin.instance = null;
		super.stop(context);
	}

	public static CodeQlPlugin get() {
		return CodeQlPlugin.instance;
	}

	public static void log(final Throwable exception) {
		if (CodeQlPlugin.instance != null) {
			CodeQlPlugin.instance.getLog().log(new Status(IStatus.ERROR, CodeQlPlugin.ID, exception.getMessage(), exception));
		}
	}
}
