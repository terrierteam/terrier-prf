# Terrier-PRF

Terrier-PRF is a development playground for PRF models in Terrier. It used to contain RM1 and RM3, but these have been promoted to the terrier-core project.

It contains one model:
 - Axiomatic Query Expansion [1,2]


## Installation

After cloning this github repo, you can 

```shell
mvn install
```

to install to your local Maven repo.

## Usage

From your Terrier directory, you should:

1. edit the terrier.properties file to specify the query expansion models in the querying.processes list

```
querying.processes=terrierql:TerrierQLParser,parsecontrols:TerrierQLToControls,parseql:TerrierQLToMatchingQueryTerms,matchopql:MatchingOpQLParser,applypipeline:ApplyTermPipeline,localmatching:LocalManager$ApplyLocalMatching,ax:AxiomaticQE,qe:QueryExpansion,labels:org.terrier.learning.LabelDecorator,filters:LocalManager$PostFilterProcess'

```

2. Invoke `batchretrieval` or `interactive` command while specifying the relevant controls, and the terrier-prf package

```shell

bin/terrier br -w BM25 -c axqe:on -o ./bm25.axqe.res -P org.terrier:terrier-prf

```

## Use of AxiomaticQE in PyTerrier

AxiomaticQE can be used in PyTerrier.

```python
import pyterrier as pt
pt.java.add_package('com.github.terrierteam', 'terrier-prf', '-SNAPSHOT')

from pyterrier.terrier.rewrite import QueryExpansion
@pt.java.required
class AxiomaticQE(QueryExpansion):
    '''
        Performs query expansion using axiomatic query expansion.

        This transformer must be followed by a Terrier Retrieve() transformer.
        The original query is saved in the `"query_0"` column, which can be restored using `pt.rewrite.reset()`.

        Instance Attributes:
         - fb_terms(int): number of feedback terms. Defaults to 10
         - fb_docs(int): number of feedback documents. Defaults to 3
    '''
    def __init__(self, *args, fb_terms=10, fb_docs=3, **kwargs):
        """
        Args:
            index_like: the Terrier index to use
            fb_terms(int): number of terms to add to the query. Terrier's default setting is 10 expansion terms.
            fb_docs(int): number of feedback documents to consider. Terrier's default setting is 3 feedback documents.
        """
        rm = pt.java.autoclass("org.terrier.querying.AxiomaticQE")
        self.fb_terms = fb_terms
        self.fb_docs = fb_docs
        kwargs["qeclass"] = rm
        super().__init__(*args, **kwargs)

    def transform(self, queries_and_docs):
        self.qe.fbTerms = self.fb_terms
        self.qe.fbDocs = self.fb_docs
        return super().transform(queries_and_docs)
```

Example usage
```python
dataset = pt.get_dataset('vaswani')
index = dataset.get_index()
retr = pt.terrier.Retriever(index, wmodel='BM25')
qe = AxiomaticQE(index)
ax_pipeline = retr >> qe >> retr
pt.Expertiment(
    [retr, qe]
    dataset.get_topics(),
    dataset.get_qrels()
    ["map"],
    names=["BM25", "AxQE"]
)
```

Example test case
```python

def test_axiomatic_qe_expansion_for_query_compact_on_bm25(self):
        # just ensure that AxiomaticQE results do not change
        expected = 'applypipeline:off compact^1.000000000'

        indexref = pt.datasets.get_dataset("vaswani").get_index()
        queriesIn = pd.DataFrame([["1", "compact"]], columns=["qid", "query"])

        qe = pt.rewrite.AxiomaticQE(indexref)
        br = pt.terrier.Retriever(indexref, wmodel='BM25')

        actual = qe.transform(br.transform(queriesIn))

        self.assertEqual(len(actual), 1)
        self.assertEqual(expected, actual.iloc[0]["query"])


```

## Credits

- Craig Macdonald, University of Glasgow
- Nicola Tonellotto, University of Pisa

Thanks to Jeff Dalton and Jimmy Lin for useful discussions.

## References

[1] Hui Fang, Chang Zhai.: Semantic term matching in axiomatic approaches to information retrieval. In: Proceedings of the 29th Annual International ACM SIGIR Conference on Research and Development in Information Retrieval, pp. 115–122. SIGIR 2006. ACM, New York (2006). 

[2] Peilin Yang and Jimmy Lin, Reproducing and Generalizing Semantic Term Matching in Axiomatic Information Retrieval. In Proceedings of ECIR  2019.
