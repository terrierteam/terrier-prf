package org.terrier.querying;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Queue;
import java.util.Random;

import com.google.common.collect.MinMaxPriorityQueue;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terrier.matching.MatchingQueryTerms;
import org.terrier.matching.models.Idf;
import org.terrier.querying.parser.SingleTermQuery;
import org.terrier.structures.EntryStatistics;
import org.terrier.structures.Index;
import org.terrier.structures.postings.IterablePosting;
import org.terrier.utility.ApplicationSetup;
import org.terrier.utility.Rounding;

import gnu.trove.TIntArrayList;
import gnu.trove.TIntDoubleHashMap;
import gnu.trove.TIntHashSet;
import gnu.trove.TIntIntHashMap;
import gnu.trove.TLongIntHashMap;

/**
 * This implements axiomatic query expansion - c.f. Fang, H., Zhai, C.: Semantic
 * term matching in axiomatic approaches to information retrieval. In:
 * Proceedings of the 29th Annual International ACM SIGIR Conference on Research
 * and Development in Information Retrieval, pp. 115–122. SIGIR 2006. ACM, New
 * York (2006). In particular, we follow the implementation documented by Yang
 * and Lin: Peilin Yang and Jimmy Lin, Reproducing and Generalizing Semantic
 * Term Matching in Axiomatic Information Retrieval. In Proceedings of ECIR
 * 2019.
 * <p><b>Properties:</b>
 * <ul>
 * <li>expansion.terms - number of terms to insert</li>
 * <li>expansion.documents - number of PRF documents to analyse</li>
 * <li>ax.beta - weight of new terms</li>
 * <li>ax.K - number of terms related to each original query term to keep</li>
 * <li>ax.R - number of random non-relevant documents to analyse</li>
 * </ul>
 * @author Craig Macdonald
 */
public class AxiomaticQE extends QueryExpansion {

	protected static Logger axlogger = LoggerFactory.getLogger(AxiomaticQE.class);

	static Comparator<Pair<Integer, Double>> pairComparator = (Pair<Integer, Double> p1,
			Pair<Integer, Double> p2) -> Double.compare(p2.getValue(), p1.getValue());

	/** beta - weight of new terms */
	final double BETA = Double.parseDouble(ApplicationSetup.getProperty("ax.beta", "0.6d"));
	
	final int K = Integer.parseInt(ApplicationSetup.getProperty("ax.K", "1000"));
	final int R = Integer.parseInt(ApplicationSetup.getProperty("ax.R", "10"));;
	Index index;

	class AxiomaticExpansionTerms extends ExpansionTerms {

		TIntHashSet seenDocids = new TIntHashSet();
		TIntHashSet originalQTerms = new TIntHashSet();
		Idf idfI = new Idf(index.getCollectionStatistics().getNumberOfDocuments());

		TLongIntHashMap pairCount = new TLongIntHashMap();
		TIntIntHashMap singleCounts = new TIntIntHashMap();

		public AxiomaticExpansionTerms() {}

		public void setOriginalQueryTerms(MatchingQueryTerms query) {
			String[] terms = query.getTerms();
			this.originalTermids.clear();
			for (int i = 0; i < terms.length; i++) {
				EntryStatistics te = query.getStatistics(terms[i]);
				if (te != null) {
					this.originalQTerms.add(te.getTermId());
				}
			}
		}

		@Override
		public SingleTermQuery[] getExpandedTerms(int M) {
			try {
				analyseDocuments(seenDocids.toArray());
			} catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}

			TIntDoubleHashMap allTerms = new TIntDoubleHashMap();

			for (int originalQueryTerm : originalQTerms.toArray()) {
				Queue<Pair<Integer, Double>> newtermQueue = MinMaxPriorityQueue.orderedBy(pairComparator).maximumSize(K)
						.create();

				for (int candidate : singleCounts.keys()) {
					if (candidate == originalQueryTerm)
						continue;
					double mi = 1;
					if (!originalQTerms.contains(candidate))
						mi = BETA * MI(originalQueryTerm, candidate);
					double idf = idfI
							.idf(index.getLexicon().getLexiconEntry(candidate).getValue().getDocumentFrequency());
					newtermQueue.add(Pair.of(candidate, mi * idf));
				}
				axlogger.info("First: " + newtermQueue.peek() + " ["
						+ index.getLexicon().getLexiconEntry(newtermQueue.peek().getKey()).getKey() + "]");
				for (Pair<Integer, Double> candidate : newtermQueue) {
					allTerms.adjustOrPutValue(candidate.getKey(), candidate.getValue(), candidate.getValue());
				}
			}

			Queue<Pair<Integer, Double>> newtermQueue = MinMaxPriorityQueue.orderedBy(pairComparator).maximumSize(M)
					.create();
			allTerms.forEachEntry((int a, double b) -> {
				newtermQueue.add(Pair.of(a, b));
				return true;
			});

			return newtermQueue.stream().map(p -> {
				SingleTermQuery term = new SingleTermQuery(index.getLexicon().getLexiconEntry(p.getLeft()).getKey());
				term.setWeight(p.getValue());
				return term;
			}).toArray(SingleTermQuery[]::new);
		}

		@Override
		public int getNumberOfUniqueTerms() {
			return -1;
		}

		protected double MI(int term1, int term2) {
			int swap;
			if (term1 > term2) {
				swap = term1;
				term1 = term2;
				term2 = swap;
			}

			final double total = (double) seenDocids.size();
			final double x1 = singleCounts.get(term1);
			final double y1 = singleCounts.get(term2);
			final double x0 = total - x1;
			final double y0 = total - y1;

			final double pX0 = x0 / total;
			final double pX1 = x1 / total;
			final double pY0 = y0 / total;
			final double pY1 = y1 / total;

			// doc num that x and y cooccurr
			final double numXY11 = pairCount.get((((long) term1) << 32) | (term2 & 0xffffffffL));
			final double numXY10 = x1 - numXY11; // doc num that x occurs but y doesn't
			final double numXY01 = y1 - numXY11; // doc num that y occurs but x doesn't
			final double numXY00 = total - numXY11 - numXY10 - numXY01; // doc num that neither x nor y occurr

			final double pXY11 = numXY11 / total;
			final double pXY10 = numXY10 / total;
			final double pXY01 = numXY01 / total;
			final double pXY00 = numXY00 / total;

			double m00 = 0, m01 = 0, m10 = 0, m11 = 0;
			if (pXY00 != 0) {
				m00 = pXY00 * Math.log(pXY00 / (pX0 * pY0));
			}

			if (pXY01 != 0) {
				m01 = pXY01 * Math.log(pXY01 / (pX0 * pY1));
			}

			if (pXY10 != 0) {
				m10 = pXY10 * Math.log(pXY10 / (pX1 * pY0));
			}

			if (pXY11 != 0) {
				m11 = pXY11 * Math.log(pXY11 / (pX1 * pY1));
			}

			double score = m00 + m10 + m01 + m11;
			// if (score < 0)
			// {
			// axlogger.info("doccount=" + total + " t1=" + x1 + " t2=" + y1
			// + " both=" + numXY11 + " neither="+ numXY00
			// + " t1nott2=" + numXY10 + " t2nott1="+numXY01);
			// axlogger.info("MI [" + term1 + "," + term2 + "]=" + score);
			// }
			return score;
		}

		protected void analyseDocuments(int[] docids) throws IOException {
			axlogger.info("Analysing " + docids.length + " documents");
			Arrays.sort(docids);
			// sort the docids: in practice, often this results in quicker access, 
			// as it reduces random seeks on the index files (docs may be clustered)
			for (int docid : docids) {
				IterablePosting ip = index.getDirectIndex()
						.getPostings(index.getDocumentIndex().getDocumentEntry(docid));
				int termid;
				TIntArrayList seenTerms = new TIntArrayList();
				while ((termid = ip.next()) != IterablePosting.EOL) {
					seenTerms.add(termid);
					singleCounts.adjustOrPutValue(termid, 1, 1);
				}
				final int[] terms = seenTerms.toNativeArray();
				for (int term1 : terms) {
					for (int term2 : terms) {
						if (term2 <= term1)
							continue;
						//we only count pairs that involve an original query term
						if (this.originalQTerms.contains(term1) || this.originalQTerms.contains(term2))
						{
							// use a long to encode both of the termids
							pairCount.adjustOrPutValue((((long) term1) << 32) | (term2 & 0xffffffffL), 1, 1);
						}
					}
				}
				ip.close();
			}
			if (singleCounts.size() > 0)
				assert pairCount.size() > 0;
			axlogger.info("Done: " + singleCounts.size() + " terms, " + pairCount.size() + " pairs");
		}

		@Override
		public void insertDocument(FeedbackDocument doc) throws IOException {
			seenDocids.add(doc.docid);
		}

	}

	@Override
	public boolean expandQuery(MatchingQueryTerms query, Request rq) throws IOException {

		int numberOfTermsToReweight = Math.max(ApplicationSetup.EXPANSION_TERMS, query.size());
		if (ApplicationSetup.EXPANSION_TERMS == 0)
			numberOfTermsToReweight = 0;

		if (selector == null)
			selector = this.getFeedbackSelector(rq);
		if (selector == null)
			return false;
		FeedbackDocument[] feedback = selector.getFeedbackDocuments(rq);
		if (feedback == null || feedback.length == 0)
			return false;

		Random r = new Random(getSeed(query));
		TIntHashSet docids = new TIntHashSet();
		int N = ApplicationSetup.EXPANSION_DOCUMENTS;

		axlogger.info("Axiomatic: K=" + K + " N=" + N + " M=" + numberOfTermsToReweight + " R=" + R + " beta=" + BETA);

		ExpansionTerms expansionTerms = getExpansionTerms();
		for (FeedbackDocument doc : feedback) {
			docids.add(doc.docid);
			N++;
			expansionTerms.insertDocument(doc);
		}

		// we insert some other random (assumed non-relevant) documents from the collection,
		// excluding the first R documents
		for (int i = 0; i < (N - 1) * R; ) {
			int otherDoc = r.nextInt(collStats.getNumberOfDocuments());
			if ( ((AxiomaticExpansionTerms) expansionTerms).seenDocids.contains(otherDoc))
				continue;
			docids.add(otherDoc);
			expansionTerms.insertDocument(new FeedbackDocument(otherDoc, 0, 0f));
			i++;
		}
		expansionTerms.setOriginalQueryTerms(query);
		SingleTermQuery[] expandedTerms = expansionTerms.getExpandedTerms(numberOfTermsToReweight);
		for (int i = 0; i < expandedTerms.length; i++) {
			SingleTermQuery expandedTerm = expandedTerms[i];
			query.addTermPropertyWeight(expandedTerm.getTerm(), expandedTerm.getWeight());
			if (axlogger.isDebugEnabled()) {
				axlogger.debug(
						"term " + expandedTerms[i].getTerm() + " appears in expanded query with normalised weight: "
								+ Rounding.toString(query.getTermWeight(expandedTerms[i].getTerm()), 4));
			}
		}
		return true;
	}

	private int getSeed(MatchingQueryTerms query) {
		String num = query.getQueryId().replaceAll("\\D+", "");
		if (num.length() == 0)
			return 13081982;
		return Integer.parseInt(num);
	}

	@Override
	public void configureIndex(Index index) {
		this.index = index;
		super.configureIndex(index);
	}

	@Override
	protected ExpansionTerms getExpansionTerms() {
		return new AxiomaticExpansionTerms();
	}

	@Override
	public String getInfo() {
		return this.getClass().getSimpleName();
	}
}
