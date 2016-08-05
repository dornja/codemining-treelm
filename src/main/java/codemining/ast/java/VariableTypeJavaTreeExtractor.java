/**
 *
 */
package codemining.ast.java;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.SimpleName;

import codemining.ast.AstNodeSymbol;
import codemining.ast.TreeNode;
import codemining.java.codeutils.scopes.VariableScopeExtractor;
import codemining.java.codeutils.scopes.VariableScopeExtractor.Variable;

import com.google.common.collect.Multimap;

/**
 * A tree format that adds an extra node with the type of the variables, not
 * just names.
 *
 * @author Miltos Allamanis <m.allamanis@ed.ac.uk>
 *
 */
public class VariableTypeJavaTreeExtractor extends JavaAstTreeExtractor {

	public class VariableTypeTreeExtractor extends TreeNodeExtractor {

		/**
		 * The variables defined at each ASTNode
		 */
		final Multimap<ASTNode, Variable> definedVariables;

		public VariableTypeTreeExtractor(final ASTNode extracted,
				final boolean useComments) {
			super(useComments);
			// TODO: Use approximate type inference or bindings
			definedVariables = VariableScopeExtractor
					.getDefinedVarsPerNode(extracted.getRoot());
		}

		/**
		 * Return the templetized form of the node, if any.
		 *
		 * @param node
		 * @return
		 */
		protected TreeNode<Integer> getTempletizedSubtreeForNode(
				final SimpleName node, final TreeNode<Integer> treeNode) {
			// is it a variable?
			final Collection<Variable> declaredVariables = definedVariables
					.get(node);
			boolean isVariable = false;
			Variable nodeVariable = null;
			for (final Variable variable : declaredVariables) {
				if (variable.name.equals(node.toString())) {
					isVariable = true;
					nodeVariable = variable;
					break;
				}
			}

			// it is not a variable. return the real symbol.
			if (!isVariable || nodeVariable == null) {
				return treeNode;
			}

			// it is a variable.
			final AstNodeSymbol symbol = constructTypeSymbol(nodeVariable.type);
			final int symbolId = getOrAddSymbolId(symbol);
			final TreeNode<Integer> templetized = TreeNode.create(symbolId, 0);
			// templetized.addChildNode(treeNode, 0);
			return templetized;
		}

		@Override
		public TreeNode<Integer> postProcessNodeBeforeAdding(
				final TreeNode<Integer> treeNode, final ASTNode node) {
			if (node.getNodeType() == ASTNode.CHARACTER_LITERAL) {
				final AstNodeSymbol symbol = constructTypeSymbol("%CHAR_LITERAL%");
				final TreeNode<Integer> literalTemplatedTree = TreeNode.create(
						getOrAddSymbolId(symbol), 1);
				literalTemplatedTree.addChildNode(treeNode, 0);
				return literalTemplatedTree;
			} else if (node.getNodeType() == ASTNode.STRING_LITERAL) {
				final AstNodeSymbol symbol = constructTypeSymbol("%STRING_LITERAL%");
				final TreeNode<Integer> literalTemplatedTree = TreeNode.create(
						getOrAddSymbolId(symbol), 1);
				literalTemplatedTree.addChildNode(treeNode, 0);
				return literalTemplatedTree;
			} else if (node.getNodeType() == ASTNode.NUMBER_LITERAL) {
				final AstNodeSymbol symbol = constructTypeSymbol("%NUM_LITERAL%");
				final TreeNode<Integer> literalTemplatedTree = TreeNode.create(
						getOrAddSymbolId(symbol), 1);
				literalTemplatedTree.addChildNode(treeNode, 0);
				return literalTemplatedTree;
			} else if (node.getNodeType() == ASTNode.SIMPLE_NAME) {
				return getTempletizedSubtreeForNode((SimpleName) node, treeNode);
			} else {
				return treeNode;
			}

		}
	}

	private static final long serialVersionUID = -5114231756353587653L;

	public static final String TEMPLETIZED_VAR_TYPE_PROPERTY = "TYPE";

	/**
	 * Return an ASTNodeSymbol.
	 *
	 * @param id
	 * @return
	 */
	public static AstNodeSymbol constructTypeSymbol(final String type) {
		final AstNodeSymbol symbol = new AstNodeSymbol(
				AstNodeSymbol.TEMPLATE_NODE);
		symbol.addChildProperty("CHILD");
		symbol.addAnnotation(TEMPLETIZED_VAR_TYPE_PROPERTY, type);

		return symbol;
	}

	public static boolean isVariableSymbol(final AstNodeSymbol symbol) {
		return symbol.hasAnnotation(TEMPLETIZED_VAR_TYPE_PROPERTY);
	}

	public VariableTypeJavaTreeExtractor() {
		super();
	}

	/**
	 * Remove any template symbols in the tree.
	 *
	 * @param tree
	 * @return
	 */
	public TreeNode<Integer> detempletize(final TreeNode<Integer> fromTree) {
		if (getSymbol(fromTree.getData()).nodeType == AstNodeSymbol.TEMPLATE_NODE) {
			return detempletize(fromTree.getChild(0, 0));
		}
		final TreeNode<Integer> toTree = TreeNode.create(fromTree.getData(),
				fromTree.nProperties());
		final ArrayDeque<TreeNode<Integer>> toStack = new ArrayDeque<TreeNode<Integer>>();
		final ArrayDeque<TreeNode<Integer>> fromStack = new ArrayDeque<TreeNode<Integer>>();

		toStack.push(toTree);
		fromStack.push(fromTree);

		while (!toStack.isEmpty()) {
			final TreeNode<Integer> currentTo = toStack.pop();
			final TreeNode<Integer> currentFrom = fromStack.pop();

			final List<List<TreeNode<Integer>>> children = currentFrom
					.getChildrenByProperty();

			for (int i = 0; i < children.size(); i++) {
				final List<TreeNode<Integer>> childrenForProperty = children
						.get(i);

				for (final TreeNode<Integer> fromChild : childrenForProperty) {
					final AstNodeSymbol symbol = getSymbol(fromChild.getData());

					if (symbol.nodeType == AstNodeSymbol.TEMPLATE_NODE) {
						TreeNode<Integer> templateChild = fromChild;
						while (!templateChild.isLeaf() && getSymbol(templateChild.getData()).nodeType == AstNodeSymbol.TEMPLATE_NODE) {
							checkArgument(templateChild.nProperties() == 1);

							templateChild = templateChild.getChild(0, 0);
						}
						if (templateChild.isLeaf()
								&& getSymbol(templateChild.getData()).nodeType == AstNodeSymbol.TEMPLATE_NODE) {
							continue;
						}
						final TreeNode<Integer> untempletizedCopyChild = TreeNode
								.create(templateChild.getData(),
										templateChild.nProperties());
						currentTo.addChildNode(untempletizedCopyChild, i);

					} else {
						final TreeNode<Integer> toChild = TreeNode.create(
								fromChild.getData(), fromChild.nProperties());
						currentTo.addChildNode(toChild, i);
						toStack.push(toChild);
						fromStack.push(fromChild);
					}
				}
			}
		}

		return toTree;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * codemining.lm.grammar.tree.AbstractEclipseTreeExtractor#getASTFromTree
	 * (codemining.lm.grammar.tree.TreeNode)
	 */
	@Override
	public ASTNode getASTFromTree(final TreeNode<Integer> tree) {
		final TreeNode<Integer> detempletized = detempletize(tree);
		return super.getASTFromTree(detempletized);
	}

	@Override
	public TreeNode<Integer> getTree(final ASTNode node,
			final boolean useComments) {
		final VariableTypeTreeExtractor ex = new VariableTypeTreeExtractor(
				node, useComments);
		ex.extractFromNode(node);
		return ex.computedNodes.get(node);
	}

	@Override
	public Map<ASTNode, TreeNode<Integer>> getTreeMap(final ASTNode node) {
		final VariableTypeTreeExtractor ex = new VariableTypeTreeExtractor(
				node, false);
		ex.extractFromNode(node);
		return ex.computedNodes;
	}

}
