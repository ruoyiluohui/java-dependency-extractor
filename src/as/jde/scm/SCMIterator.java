package as.jde.scm;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import org.eclipse.jdt.core.dom.CompilationUnit;

import as.jde.ast.BlockingBindingResolver;
import as.jde.ast.JavaFileParser;
import as.jde.ast.ASTVisitor;
import as.jde.diff.FileChange;
import as.jde.diff.Range;
import as.jde.diff.UnifiedDiffParser;
import as.jde.graph.CallGraph;
import as.jde.scm.git.GitController;
import as.jde.util.JProject;
import as.jde.util.JavaJarLocator;

public class SCMIterator {
	private GitController fGit = null;
	private int fCurrentCommit = -1;
	private List<String> fCommits = null;
	
	private JProject fProject = null;
	private Pattern fIgnoreFolderPattern;
	
	public SCMIterator(GitController gitController, String ignoreFolderRegExp) {
		fGit = gitController;
		fCommits = fGit.getAllCommits();
		fIgnoreFolderPattern = Pattern.compile(ignoreFolderRegExp);
	}

	public boolean hasNext() {
		return fCommits.size()-1 > fCurrentCommit ;
	}

	synchronized public CallGraph next() {
		fCurrentCommit++;
		String commitID = fCommits.get(fCurrentCommit);
		
		System.err.println(commitID);
		
		// checkout next revision
		fGit.reset(commitID);
		
		fProject = initJavaProject();

		CallGraph cg = createCallGraph(commitID);
		
		markChangedMethods(commitID, cg);
		
		return cg;
	}

	private synchronized CallGraph createCallGraph(String commitID) {
		final CallGraph cg = new CallGraph(fGit.getCommitInfo(commitID));

		if (fProject.javaFiles.size() == 0) return cg;
		
		// parse files
		JavaFileParser parser = new JavaFileParser(fProject.classPath, fProject.sourcePath.getPaths(), fGit.getRepositoryPath());

		List<String> files = new ArrayList<String>();
		for (File file : fProject.javaFiles) {
			files.add(file.getAbsolutePath());
		}
		
		final Map<String, CompilationUnit> cUnits = parser.parseFiles(files);
		
		ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		
		// visit all compilation units
		final BlockingBindingResolver bbr = new BlockingBindingResolver();
		for (final String fullyQuallifiedFilename : cUnits.keySet()) {
			Runnable r = new Runnable() {
				public void run() {
					CompilationUnit unit = cUnits.get(fullyQuallifiedFilename);
					ASTVisitor visitor = new ASTVisitor(fullyQuallifiedFilename, unit, cg, bbr);
					//System.out.println(fullyQuallifiedFilename); //打印所有的java文件
					unit.accept(visitor);					
				}
			};
			executor.execute(r);
		}
		
		executor.shutdown();
		
		while (!executor.isTerminated()) {
			try {
				this.wait(5);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		return cg;
	}

	private void markChangedMethods(String commitID, CallGraph cg) {
		UnifiedDiffParser diffParser = new UnifiedDiffParser(); 
		List<FileChange> changes = diffParser.parse(fGit.getCommitDiffHunkHeaders(commitID));
		for (FileChange change : changes) {
			String filename = change.getNewFile();
			if (filename != null && filename.endsWith("java")) {
				for (Range changeRange : change.getRanges()) {
					cg.setMethodsAsChanged(fGit.getRepositoryPath() + filename, changeRange.getStart(), changeRange.getEnd());
				}
			}
		}
	}

	private JProject initJavaProject() {
		JProject project = new JProject();
		
		String[] foldersToIgnrore = {".git"};
		
		JavaJarLocator locator = new JavaJarLocator();
		locator.locate(new File(fGit.getRepositoryPath()), foldersToIgnrore, fIgnoreFolderPattern);
		project.classPath = locator.getJarFiles();
		project.sourcePath = locator.getJavaFilePaths();
		project.javaFiles = locator.getJavaFiles();
		System.out.println(project.javaFiles );
		
		return project;
	}
}
