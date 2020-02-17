package org.terrier.querying;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terrier.matching.MatchingQueryTerms;
import org.terrier.matching.MatchingQueryTerms.MatchingTerm;
import org.terrier.matching.matchops.SingleTermOp;
import org.terrier.querying.parser.Query.QTPBuilder;
import org.terrier.structures.Index;

import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;

/**
 * RM3 preliminary implementation
 * 
 * @author Nicola Tonellotto and Craig Macdonald
 */
@ProcessPhaseRequisites({ManagerRequisite.MQT, ManagerRequisite.RESULTSET})
public class RM3 extends RM1
{

    protected static Logger logger = LoggerFactory.getLogger(RM3.class);

    protected static final float DEFAULT_LAMBDA = 0.6F;
    protected Int2FloatMap originalQueryTermScores;
    protected float lambda;

    public RM3(final int fbTerms, final int fbDocs, final Index index) 
    {
        this(fbTerms, fbDocs, index, DEFAULT_LAMBDA);
    }

    public RM3(final int fbTerms, final int fbDocs, final Index index, final float lambda) 
    {
        super(fbTerms, fbDocs, index);
        
        this.originalQueryTermScores = new Int2FloatOpenHashMap();
        this.lambda = lambda;
    }

    public RM3()
    {
        super();
        this.originalQueryTermScores = new Int2FloatOpenHashMap();
        this.lambda = DEFAULT_LAMBDA;
    }

    public boolean expandQuery(MatchingQueryTerms mqt, Request rq) throws IOException
	{
        this.index = rq.getIndex();
        computeOriginalTermScore(mqt);
        if (rq.hasControl("rm3.lambda"))
            this.lambda = Float.parseFloat( rq.getControl("rm3.lambda") ) ;
		List<ExpansionTerm> expansions = this.expand(rq);
        mqt.clear();
        for (ExpansionTerm et : expansions)
		{
			mqt.add(QTPBuilder.of(new SingleTermOp(et.getText())).setWeight(et.getWeight()).build());
        }
        logger.info("Reformulated query: " + mqt.toString());
		return true;
    }
    

    protected void computeOriginalTermScore(final MatchingQueryTerms mqt) 
    {
        final float queryLength = (float) mqt.stream().map(mt -> mt.getValue().getWeight()).mapToDouble(Double::doubleValue).sum();
        for (MatchingTerm mt : mqt) {

            int termid = super.index.getLexicon().getLexiconEntry(mt.getKey().toString()).getTermId();
            if (termid == -1)
                continue;
    		float termCount = (float) mt.getValue().getWeight();
    		originalQueryTermScores.put(termid, termCount / queryLength);
    	}
	}

    @Override
	protected void computeFeedbackTermScores()
    {
        super.computeFeedbackTermScores();
        
        for (int termid : feedbackTermScores.keySet()) {
            if (originalQueryTermScores.containsKey(termid)) {
            	float weight = lambda * originalQueryTermScores.get(termid) + (1 - lambda) * feedbackTermScores.get(termid);
            	feedbackTermScores.put(termid, weight);
            }
        }
        
        for (int termid : originalQueryTermScores.keySet()) {
            if (!feedbackTermScores.containsKey(termid)) {
            	float weight = lambda * originalQueryTermScores.get(termid);
            	feedbackTermScores.put(termid, weight);
            }
        }
    }
}