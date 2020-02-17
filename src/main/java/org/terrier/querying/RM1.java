package org.terrier.querying;

import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terrier.matching.MatchingQueryTerms;
import org.terrier.matching.ResultSet;
import org.terrier.structures.Index;
import org.terrier.structures.LexiconEntry;
import org.terrier.structures.postings.IterablePosting;
import org.terrier.utility.ApplicationSetup;

import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.Getter;
import lombok.Setter;

/**
 * RM1 preliminary implementation
 * 
 * See: http://people.cs.vt.edu/~jiepu/cs5604_fall2018/10_qm.pdf
 * 
 * @author Nicola Tonellotto
 */
@ProcessPhaseRequisites({ManagerRequisite.MQT, ManagerRequisite.RESULTSET})
public class RM1 implements MQTRewritingProcess
{

	protected static Logger logger = LoggerFactory.getLogger(RM1.class);

	/**
	 * This class represents a simple expansion term struct.
	 * 
	 * @author Nicola Tonellotto
	 */
	static class ExpansionTerm 
	{
		@Getter protected int termid;
		@Getter protected String text;
		@Getter protected double weight;
		
		public ExpansionTerm(final int termid, final String text, final double weight) 
		{
			this.termid = termid;
			this.text   = text;
			this.weight = weight;
		}
	}

	/**
	 * This class represents one of the top documents used to perform pseudo-relevance feedback.
	 * It is composed by a list of terms composing the document, with the term frequency associated to each term in doc,
	 * its original score, computed by any matching model, e.g., BM25, and its length, i.e., the sum of the term frequencies.
	 * 
	 * @author Nicola Tonellotto
	 */
	public class FeedbackDocument 
	{
		protected final int MIN_DF = Integer.parseInt(System.getProperty("prf.mindf", "2"));
		
		// if a term appears in more than 10% of documents, we ignore it
		protected final double MAX_DOC_PERCENTAGE = Float.parseFloat(System.getProperty("prf.maxdp", "0.1"));
		
		// termid -> term frequency in document map
		protected Int2IntMap terms;
		
		protected @Getter int length;
		protected @Getter double originalScore;
		protected @Getter double qlScore;
		
		public FeedbackDocument(final int docid, final double originalScore, final Index index) throws IOException
		{
			this.originalScore = originalScore;
			
			this.terms = new Int2IntOpenHashMap();
			
			final int MAX_DOC_FREQ = (int) (MAX_DOC_PERCENTAGE * index.getCollectionStatistics().getNumberOfDocuments());	
			final IterablePosting dp = index.getDirectIndex().getPostings(index.getDocumentIndex().getDocumentEntry(docid));
			while (dp.next() != IterablePosting.EOL) {
				LexiconEntry le = index.getLexicon().getLexiconEntry(dp.getId()).getValue();
				if (le.getDocumentFrequency() >= MIN_DF && le.getDocumentFrequency() < MAX_DOC_FREQ)
					this.terms.put(dp.getId(), dp.getFrequency());
			}
			dp.close();
			
			this.length = index.getDocumentIndex().getDocumentLength(docid);

		}
		
		public IntSet getTermIds()
		{
			return terms.keySet();
		}
			
		public long getFrequency(final int termid)
		{
			return terms.get(termid);
		}	
	}

	protected final int fbTerms;
	protected final int fbDocs;
	protected Index index = null;
	
	protected IntSet topLexicon;
	protected List<FeedbackDocument> topDocs;
	protected Int2FloatMap feedbackTermScores;
	
	@Setter protected double lambda = 1.0;
	
	/**
	 * Constructor
	 * 
	 * @param fbTerms how many feedback terms to return
	 * @param fbDocs how many feedback documents to use (should be less than or equal to the top documents)
	 * @param index the index to used to access the direct index postings
	 */
	public RM1(final int fbTerms, final int fbDocs, final Index index)
	{
		this.fbTerms = fbTerms;
		this.fbDocs  = fbDocs;
		this.index = index;
		
		this.topLexicon         = new IntOpenHashSet();
		this.topDocs            = new ObjectArrayList<>();
		this.feedbackTermScores = new Int2FloatOpenHashMap();
	}

	public RM1()
	{
		this.topLexicon         = new IntOpenHashSet();
		this.topDocs            = new ObjectArrayList<>();
		this.feedbackTermScores = new Int2FloatOpenHashMap();
		this.fbTerms = ApplicationSetup.EXPANSION_TERMS;
		this.fbDocs = ApplicationSetup.EXPANSION_DOCUMENTS;
	}

	public void process(Manager manager, Request q) {
		try{
			this.expandQuery(q.getMatchingQueryTerms(), q);
		}catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	/** MQTRewriting implementation. */
	public boolean expandQuery(MatchingQueryTerms mqt, Request rq) throws IOException
	{
		this.index = rq.getIndex();
		List<ExpansionTerm> expansions = this.expand(rq);
		mqt.clear();
		for (ExpansionTerm et : expansions)
		{
			mqt.add(QTPBuilder.of(new SingleTermOp(et.getText())).setWeight(et.getWeight()).build());
		}
		logger.info("Reformulated query: " + mqt.toString());∏
		return true;
	}
	
	/**
	 * This method computes a list of expansion terms from a given search request from Terrier
	 * 
	 * @param srq the processed search request from Terrier containing the top documets' docids & scores
	 * 
	 * @return a list of expansion terms
	 * 
	 * @throws IOException if there are problems in accessing the direct index
	 */
	public List<ExpansionTerm> expand(final Request srq) throws IOException
	{
		this.topLexicon.clear();
		this.topDocs.clear();
		this.feedbackTermScores.clear();

		retrieveTopDocuments(srq.getResultSet());	
		computeFeedbackTermScores();
		
		clipTerms();
		normalizeFeedbackTermScores();
		
		List<ExpansionTerm> rtr = new ObjectArrayList<>();
		for (int termid: feedbackTermScores.keySet())
			rtr.add(new ExpansionTerm(termid, index.getLexicon().getLexiconEntry(termid).getKey(), feedbackTermScores.get(termid)));
		return rtr;
	}

	/**
	 * This method retrieves from the direct index all terms if the top documents with the necessary statistics.

	 * @param rs the search request returned by Terrier with top documents' docids & scores
	 * 
	 * @throws IOException if there are problems in accessing the direct index
	 */
	protected void retrieveTopDocuments(final ResultSet rs) throws IOException 
	{	
		int numDocs = rs.getResultSize() < fbDocs ? rs.getResultSize() : fbDocs;
		double norm = logSumExp(rs.getScores());
		
		for (int i = 0; i < numDocs; ++i) {
			FeedbackDocument doc = new FeedbackDocument(rs.getDocids()[i], Math.exp(rs.getScores()[i] - norm), index);
			topDocs.add(doc);
			topLexicon.addAll(doc.getTermIds());			
		}
	}

	/**
	 * This method computes the relevance scores of all terms in the top documents according to RM1
	 */
	protected void computeFeedbackTermScores() 
	{
		for (int termid: topLexicon) {
			float fbWeight = 0.0f;
			for (FeedbackDocument doc: topDocs)
				fbWeight += (double) doc.getFrequency(termid) / (double) doc.getLength() * doc.getOriginalScore();
			feedbackTermScores.put(termid, fbWeight * (1.0f/topDocs.size())); //see galago line 231 in scoreGrams().
		}
	}

	/**
	 * This method reduces the number of feedback terms to a fixed amount
	 */
	protected void clipTerms()
	{
		feedbackTermScores = feedbackTermScores
			.int2FloatEntrySet()
			.stream()
			.sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
			.limit(fbTerms)
			.collect(toMap(Map.Entry::getKey, 
						   Map.Entry::getValue, 
						   (e1, e2) -> e2, 
						   Int2FloatOpenHashMap::new)
			);		
	}
	
	/**
	 * This method transforms the feedback term scores into a probability distribution
	 */
	protected void normalizeFeedbackTermScores() 
	{
		float norm = feedbackTermScores.values().stream().reduce(0.0f,  Float::sum);
		feedbackTermScores.replaceAll((termid, score) -> score / norm);	
	}
	
	private static double logSumExp(final double[] scores)
	{
		double max = Double.NEGATIVE_INFINITY;
		for (double score : scores)
		  max = Math.max(score, max);
	
		double sum = 0.0;
		for (int i = 0; i < scores.length; i++)
			sum += Math.exp(scores[i] - max);
		
		return max + Math.log(sum);
	}

}