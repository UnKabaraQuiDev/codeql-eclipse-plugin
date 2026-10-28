package lu.kbra.codeql.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;

import lu.kbra.codeql.scan.ScanService;

public final class CodeQlHandler extends AbstractHandler {
	@Override
	public Object execute(final ExecutionEvent event) throws ExecutionException {
		final String id = event.getCommand().getId();
		if (id.endsWith("rescanAll")) {
			ScanService.scanAll();
			return null;
		}
		if (id.endsWith("toggleMarkers")) {
			ScanService.toggleMarkers();
			return null;
		}

		final IResource selected = CodeQlHandler.selectedResource(event);
		if (id.endsWith("rescanPackage")) {
			final IContainer container = selected instanceof final IContainer i ? i
					: selected instanceof IFile ? selected.getParent()
					: null;
			if (container != null) {
				final IProject project = container.getProject();
				ScanService.scan(project,
						file -> container.getFullPath().isPrefixOf(file.getFullPath()),
						"Rescan package " + container.getName());
				return null;
			}
		} else if (id.endsWith("rescanFile")) {
			final IFile file = selected instanceof final IFile i ? i : CodeQlHandler.editorFile(event);
			if (file != null) {
				ScanService.scan(file.getProject(), candidate -> candidate.equals(file), "Rescan file " + file.getName());
				return null;
			}
		}
		MessageDialog.openInformation(HandlerUtil.getActiveShell(event),
				"CodeQL",
				"Select a source file or package in Package Explorer, or open a source file in an editor.");
		return null;
	}

	private static IResource selectedResource(final ExecutionEvent event) {
		if (HandlerUtil.getCurrentSelection(event) instanceof final IStructuredSelection selection && !selection.isEmpty()) {
			final Object first = selection.getFirstElement();
			switch (first) {
			case final IResource resource -> {
				return resource;
			}
			case final IJavaElement element -> {
				return element.getResource();
			}
			case final org.eclipse.core.runtime.IAdaptable adaptable -> {
				final IResource resource = adaptable.getAdapter(IResource.class);
				if (resource != null) {
					return resource;
				}
			}
			case null, default -> {
			}
			}
		}
		return null;
	}

	private static IFile editorFile(final ExecutionEvent event) {
		final IEditorPart editor = HandlerUtil.getActiveEditor(event);
		if (editor == null) {
			return null;
		}
		final IEditorInput input = editor.getEditorInput();
		if (input instanceof final IFileEditorInput fileInput) {
			return fileInput.getFile();
		}
		return input.getAdapter(IFile.class);
	}
}
