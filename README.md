# Terrier-PRF

Terrier-PRF provides additional pseudo-relevance feedback (query expansion) models for the Terrier platform. In particular, it contains three models:
 - RM1 relevance model [1]
 - RM3 relevance model [2]
 - Axiomatic Query Expansion [3,4]


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
querying.processes=terrierql:TerrierQLParser,parsecontrols:TerrierQLToControls,parseql:TerrierQLToMatchingQueryTerms,matchopql:MatchingOpQLParser,applypipeline:ApplyTermPipeline,localmatching:LocalManager$ApplyLocalMatching,rm1:RM1,rm3:RM3,ax:AxiomaticQE,qe:QueryExpansion,labels:org.terrier.learning.LabelDecorator,filters:LocalManager$PostFilterProcess'

```

2. Invoke `batchretrieval` or `interactive` command while specifying the relevant controls, and the terrier-prf package

```
bin/terrier br -w BM25 -c rm1:on -o ./bm25.rm1.res -P org.terrier:terrier-prf:0.1-SNAPSHOT

bin/terrier br -w BM25 -c rm3:on -o ./bm25.rm3.res -P org.terrier:terrier-prf:0.1-SNAPSHOT

bin/terrier br -w BM25 -c axqe:on -o ./bm25.axqe.res -P org.terrier:terrier-prf:0.1-SNAPSHOT

```

(0.1-SNAPSHOT) is optional for Terrier versions after 5.2

## Credits

- Craig Macdonald, University of Glasgow
- Nicola Tonellotto, University of Pisa

Thanks to Jeff Dalton and Jimmy Lin for useful discussions.

## References

[1] Victor Lavrenko and W. Bruce Croft. Relevance based language models. In Proceedings of the 24th annual international ACM SIGIR conference on Research and development in information retrieval (SIGIR ’01). https://dl.acm.org/doi/10.1145/383952.383972 

[2] Nasreen Abdul-Jaleel, James Allan, W. Bruce Croft, Fernando Diaz, Leah Larkey, Xiaoyan Li, Mark D. Smucker, Courtney Wade. UMass at TREC 2004: Novelty and HARD.  In Proceedings of TREC 2004. https://trec.nist.gov/pubs/trec13/papers/umass.novelty.hard.pdf

[3] Hui Fang, Chang Zhai.: Semantic term matching in axiomatic approaches to information retrieval. In: Proceedings of the 29th Annual International ACM SIGIR Conference on Research and Development in Information Retrieval, pp. 115–122. SIGIR 2006. ACM, New York (2006). 

[4] Peilin Yang and Jimmy Lin, Reproducing and Generalizing Semantic Term Matching in Axiomatic Information Retrieval. In Proceedings of ECIR  2019.
