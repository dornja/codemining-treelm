package codemining.lm.tsg.samplers;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import org.apache.commons.lang.exception.ExceptionUtils;

import cc.mallet.optimize.ConjugateGradient;
import cc.mallet.optimize.Optimizable;
import codemining.ast.TreeNode;
import codemining.lm.cfg.AbstractContextFreeGrammar;
import codemining.lm.cfg.AbstractContextFreeGrammar.NodeConsequent;
import codemining.lm.tsg.ITsgPosteriorProbabilityComputer;
import codemining.lm.tsg.TSGNode;
import codemining.lm.tsg.TSGrammar;
import codemining.lm.tsg.samplers.CFGPrior.IRuleCreator;
import codemining.math.distributions.GeometricDistribution;
import codemining.util.SettingsLoader;
import codemining.util.StatsUtil;
import codemining.util.parallel.ParallelThreadPool;

import com.google.common.collect.Lists;
import com.google.common.math.DoubleMath;
import com.google.common.util.concurrent.AtomicDouble;

/**
 * A TSG posterior computer given the sample. This class computes the
 * probabilities given a corpus.
 *
 * @author Miltos Allamanis <m.allamanis@ed.ac.uk>
 *
 */
public class ClassicTsgPosteriorComputer implements
		ITsgPosteriorProbabilityComputer<TSGNode>, IRuleCreator {

	private class HyperparameterOptimizable implements
			Optimizable.ByGradientValue {

		private static final double MIN_VAL = 10E-6;
		final Collection<TreeNode<TSGNode>> treeCorpus;

		public HyperparameterOptimizable(
				final Collection<TreeNode<TSGNode>> treeCorpus) {
			this.treeCorpus = treeCorpus;
		}

		@Override
		public int getNumParameters() {
			return 2;
		}

		@Override
		public double getParameter(final int index) {
			if (index == 0) {
				return concentrationParameter;
			} else if (index == 1) {
				return geometricProbability;
			}
			throw new IllegalArgumentException("We have only 2 parameters");
		}

		@Override
		public void getParameters(final double[] values) {
			values[0] = concentrationParameter;
			values[1] = geometricProbability;
		}

		@Override
		public double getValue() {
			final AtomicDouble logProbSum = new AtomicDouble(0);

			final ParallelThreadPool ptp = new ParallelThreadPool();
			for (final TreeNode<TSGNode> tree : treeCorpus) {
				for (final TreeNode<TSGNode> root : TSGNode.getAllRootsOf(tree)) {
					ptp.pushTask(new Runnable() {

						@Override
						public void run() {
							logProbSum
									.addAndGet(computeLog2PosteriorProbabilityOfRule(
											root, true));
						}

					});

				}
			}
			ptp.waitForTermination();

			return logProbSum.get();
		}

		@Override
		public void getValueGradient(final double[] gradients) {
			final AtomicDouble concentrationGradient = new AtomicDouble(0);
			final AtomicDouble geometricGradient = new AtomicDouble(0);

			final ParallelThreadPool ptp = new ParallelThreadPool();

			for (final TreeNode<TSGNode> tree : treeCorpus) {
				for (final TreeNode<TSGNode> subTree : TSGNode
						.getAllRootsOf(tree)) {
					ptp.pushTask(new Runnable() {
						@Override
						public void run() {
							final double nRulesCommonRoot = grammar
									.countTreesWithRoot(subTree.getData()) - 1;
							final double nRulesInGrammar = grammar
									.countTreeOccurences(subTree) - 1;

							checkArgument(nRulesCommonRoot >= nRulesInGrammar,
									"Counts are not correct");

							final int treeSize = subTree.getTreeSize();
							final double logRuleMLE = prior
									.getTreeCFLog2Probability(subTree);

							final double geometricLogProb = GeometricDistribution
									.getLog2Prob(treeSize, geometricProbability);

							final double priorLog2Prob = geometricLogProb
									+ logRuleMLE
									+ DoubleMath.log2(concentrationParameter);

							final double log2denominator = StatsUtil
									.log2SumOfExponentials(
											DoubleMath.log2(nRulesInGrammar),
											priorLog2Prob);
							concentrationGradient.addAndGet(Math.pow(2,
									geometricLogProb + logRuleMLE
											- log2denominator)
									- 1.
									/ (nRulesCommonRoot + concentrationParameter));

							final double geomGradient = Math.pow(
									1 - geometricProbability, treeSize - 1)
									- (treeSize - 1.)
									* geometricProbability
									* Math.pow(1 - geometricProbability,
											treeSize - 2);

							geometricGradient.addAndGet(concentrationParameter
									* Math.pow(2, logRuleMLE - log2denominator)
									* geomGradient);
						}

					});
				}
			}

			ptp.waitForTermination();

			gradients[0] = concentrationGradient.get() / Math.log(2);
			gradients[1] = geometricGradient.get() / Math.log(2);
		}

		private void limitParams() {
			if (concentrationParameter < MIN_VAL) {
				concentrationParameter = MIN_VAL;
			}
			if (geometricProbability >= 1) {
				geometricProbability = 1 - MIN_VAL;
			} else if (geometricProbability <= 0) {
				geometricProbability = MIN_VAL;
			}

		}

		@Override
		public void setParameter(final int idx, final double param) {
			if (idx == 0) {
				concentrationParameter = param;
			} else if (idx == 1) {
				geometricProbability = param;
			}
			limitParams();
		}

		@Override
		public void setParameters(final double[] params) {
			concentrationParameter = params[0];
			geometricProbability = params[1];
			limitParams();
		}
	}

	private static final long serialVersionUID = -874360828121014055L;

	static final Logger LOGGER = Logger
			.getLogger(ClassicTsgPosteriorComputer.class.getName());

	private static final boolean DO_GRADIENT_CHECK = SettingsLoader
			.getBooleanSetting("DoGradientCheck", false);

	protected double concentrationParameter;

	protected double geometricProbability;

	final TSGrammar<TSGNode> grammar;

	protected final CFGPrior prior;

	public ClassicTsgPosteriorComputer(final TSGrammar<TSGNode> grammar,
			final double avgTreeSize, final double DPconcentration) {
		this.grammar = grammar;
		prior = new CFGPrior(grammar.getTreeExtractor(), this);
		concentrationParameter = DPconcentration;
		geometricProbability = 1. / avgTreeSize;
	}
	
	public ClassicTsgPosteriorComputer( final TSGrammar<TSGNode> grammar,
			final ClassicTsgPosteriorComputer orig ) {
		this.grammar = grammar;
		this.prior = orig.prior;
		this.concentrationParameter = orig.concentrationParameter;
		this.geometricProbability = orig.geometricProbability;
	}

	@Override
	public double computeLog2PosteriorProbabilityOfRule(
			final TreeNode<TSGNode> tree, final boolean remove) {
		checkNotNull(tree);

		double nRulesCommonRoot = grammar.countTreesWithRoot(tree.getData());
		double nRulesInGrammar = grammar.countTreeOccurences(tree);

		if (nRulesInGrammar > nRulesCommonRoot) { // Concurrency has bitten
			// us... Sorry no
			// guarantees, but it's
			// the
			// most probable that we just removed it...
			nRulesInGrammar = nRulesCommonRoot;
		}

		final double log2prior = getLog2PriorForTree(tree);
		checkArgument(
				!Double.isInfinite(log2prior) && !Double.isNaN(log2prior),
				"Prior is %s", log2prior);

		if (nRulesInGrammar > 0 && remove) {
			nRulesInGrammar--;
			nRulesCommonRoot--;
		}

		final double log2Probability = StatsUtil.log2SumOfExponentials(
				DoubleMath.log2(nRulesInGrammar),
				DoubleMath.log2(concentrationParameter) + log2prior)
				- DoubleMath.log2(nRulesCommonRoot + concentrationParameter);

		checkArgument(
				!Double.isNaN(log2Probability)
						&& !Double.isInfinite(log2Probability),
				"Posterior probability is %s", log2Probability);
		checkArgument(log2Probability <= 0);
		return log2Probability;
	}

	@Override
	public AbstractContextFreeGrammar.CFGRule createRuleForNode(
			final TreeNode<TSGNode> node) {
		final List<List<TreeNode<TSGNode>>> childrenByProperty = node
				.getChildrenByProperty();
		final NodeConsequent cons = new NodeConsequent(
				childrenByProperty.size());
		for (final List<TreeNode<TSGNode>> childProperties : childrenByProperty) {
			final List<Integer> propertyChildren = Lists
					.newArrayListWithCapacity(childProperties.size());
			cons.nodes.add(propertyChildren);
			for (final TreeNode<TSGNode> child : childProperties) {
				propertyChildren.add(postprocessIdForCFG(child));
			}
		}
		return new AbstractContextFreeGrammar.CFGRule(
				postprocessIdForCFG(node), cons);
	}

	/**
	 * Get the prior probability for this tree as given by the PCFG and the
	 * geometric distribution.
	 *
	 * @param subtree
	 * @return
	 */
	public double getLog2PriorForTree(final TreeNode<TSGNode> subtree) {
		checkNotNull(subtree);
		final int treeSize = subtree.getTreeSize();
		final double logRuleMLE = prior.getTreeCFLog2Probability(subtree);

		final double geometricLogProb = GeometricDistribution.getLog2Prob(
				treeSize, geometricProbability);
		return geometricLogProb + logRuleMLE;
	}

	public CFGPrior getPrior() {
		return prior;
	}

	public void optimizeHyperparameters(
			final Collection<TreeNode<TSGNode>> treeCorpus) {

		final HyperparameterOptimizable optimizable = new HyperparameterOptimizable(
				treeCorpus);

		if (DO_GRADIENT_CHECK) {
			final double[] gradient = new double[2];
			optimizable.getValueGradient(gradient);
			final double val1 = optimizable.getValue();

			final double dx = 10E-8;
			concentrationParameter += dx;
			double val2 = optimizable.getValue();
			final double empiricalConcentrationGradient = (val2 - val1) / dx;
			System.out.println("GRADIENT CHECKING (a): computed:" + gradient[0]
					+ " empirical:" + empiricalConcentrationGradient);
			concentrationParameter -= dx;

			geometricProbability += dx;
			val2 = optimizable.getValue();
			final double empiricalGeomGradient = (val2 - val1) / dx;
			System.out.println("GRADIENT CHECKING (geom): computed:"
					+ gradient[1] + " empirical:" + empiricalGeomGradient);
			geometricProbability -= dx;
		}

		final ConjugateGradient optimizer = new ConjugateGradient(optimizable);
		optimizer.setTolerance(1E-6);

		try {
			optimizer.optimize();
		} catch (final IllegalArgumentException e) {
			// This exception may be thrown if L-BFGS
			// cannot step in the current direction.
			// This condition does not necessarily mean that
			// the optimizer has failed, but it doesn't want
			// to claim to have succeeded...
			LOGGER.severe("Failed to optimize: "
					+ ExceptionUtils.getFullStackTrace(e));
		}

		LOGGER.info("Converged at: " + concentrationParameter + ", "
				+ geometricProbability);
	}

	/**
	 * Return the id of the node. Useful for subclassing.
	 *
	 * @return
	 */
	protected int postprocessIdForCFG(final TreeNode<TSGNode> node) {
		final int originalId = node.getData().nodeKey;
		return originalId;
	}

}