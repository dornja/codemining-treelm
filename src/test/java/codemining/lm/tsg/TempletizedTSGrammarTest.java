/**
 * 
 */
package codemining.lm.tsg;

import static org.junit.Assert.assertEquals;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.eclipse.jdt.core.dom.ASTNode;
import org.junit.Before;
import org.junit.Test;

import codemining.ast.TreeNode;
import codemining.ast.java.TempletizedJavaTreeExtractor;
import codemining.java.codeutils.JavaASTExtractor;
import codemining.languagetools.ParseType;
import codemining.lm.tsg.TSGNode;
import codemining.lm.tsg.TempletizedTSGrammar;

/**
 * @author Miltiadis Allamanis <m.allamanis@ed.ac.uk>
 * 
 */
public class TempletizedTSGrammarTest {

	private String classContent;
	private String classContent2;
	private String methodContent;

	private void assertReparametrizationUnaffected(final String code,
			final ParseType parseType, final boolean useComments) {
		final JavaASTExtractor ex = new JavaASTExtractor(false, useComments);
		final ASTNode cu = ex.getAST(code, parseType);
		final TempletizedJavaTreeExtractor converter = new TempletizedJavaTreeExtractor();
		final TempletizedTSGrammar grammar = new TempletizedTSGrammar(converter);
		final TreeNode<Integer> treeCu = converter.getTree(cu, useComments);
		final TreeNode<TSGNode> treeCu2 = TSGNode.convertTree(treeCu, 0);

		assertEquals(treeCu.getTreeSize(), treeCu2.getTreeSize());
		assertEquals(treeCu.getTreeSize(), grammar.reparametrizeTree(treeCu2)
				.getTreeSize());

		assertEquals(
				grammar.reparametrizeTree(grammar.reparametrizeTree(treeCu2)),
				grammar.reparametrizeTree(treeCu2));
	}

	@Test
	public void checkReparametrization() {
		assertReparametrizationUnaffected(classContent,
				ParseType.COMPILATION_UNIT, false);
		assertReparametrizationUnaffected(classContent2,
				ParseType.COMPILATION_UNIT, false);
		assertReparametrizationUnaffected(methodContent, ParseType.METHOD,
				false);
	}

	@Test
	public void checkReparametrizationWithComments() {
		assertReparametrizationUnaffected(classContent,
				ParseType.COMPILATION_UNIT, true);
		assertReparametrizationUnaffected(classContent2,
				ParseType.COMPILATION_UNIT, true);
		assertReparametrizationUnaffected(methodContent, ParseType.METHOD, true);
	}

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		classContent = FileUtils.readFileToString(new File(
				TempletizedTSGrammarTest.class.getClassLoader()
						.getResource("SampleClass.txt").getFile()));

		classContent2 = FileUtils.readFileToString(new File(
				TempletizedTSGrammarTest.class.getClassLoader()
						.getResource("SampleClass2.txt").getFile()));

		methodContent = FileUtils.readFileToString(new File(
				TempletizedTSGrammarTest.class.getClassLoader()
						.getResource("SampleMethod.txt").getFile()));
	}
}
