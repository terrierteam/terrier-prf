# Terrier-PRF

Terrier-PRF provides additional pseudo-relevance feedback (query expansion) models for the Terrier platform. In particular, it contains three models:
 - RM1 relevance model
 - RM3 relevance model
 - Axiomatic Query Expansion


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
bin/terrier br -w BM25 -c rm1:on -o ./bm25.rm1.res -P org.terrier:terrier-prf

bin/terrier br -w BM25 -c rm3:on -o ./bm25.rm3.res -P org.terrier:terrier-prf

bin/terrier br -w BM25 -c axqe:on -o ./bm25.axqe.res -P org.terrier:terrier-prf

```

## Credits

Craig Macdonald, University of Glasgow
Nicola Tonellotto, University of Pisa

Thanks to Jeff Dalton and Jimmy Lin for useful discussions.
