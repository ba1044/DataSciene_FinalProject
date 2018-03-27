@file:JvmName("KotlinAbstractAnalyzer")
package edu.unh.cs980.language

import edu.unh.cs980.getIndexSearcher
import edu.unh.cs980.misc.AnalyzerFunctions
import info.debatty.java.stringsimilarity.Jaccard
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.FuzzyQuery
import org.apache.lucene.search.IndexSearcher
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.experimental.buildSequence


data class RelevantEntity(val name: String, val content: String, val statContainer: CorpusStatContainer,
                          val score: Double, val rank: Double, val queryLikelihood: LikelihoodContainer)

class KotlinAbstractAnalyzer(val indexSearcher: IndexSearcher) {
    constructor(indexLoc: String) : this(getIndexSearcher(indexLoc))

//    val analyzer = StandardAnalyzer()
    val gramAnalyzer = KotlinGramAnalyzer(indexSearcher)
    val sim = Jaccard()
    private val memoizedAbstractDocs = ConcurrentHashMap<String, Document?>()
    private val memoizedAbstractStats = ConcurrentHashMap<String, LanguageStatContainer?>()


    fun retrieveEntityStatContainer(entity: String): LanguageStatContainer? =
            memoizedAbstractStats.computeIfAbsent(entity, {key ->
                val entityDoc = retrieveEntityDoc(key)
                entityDoc?.let { doc -> gramAnalyzer.getLanguageStatContainer(doc.get("text")) }
            })

    fun retrieveEntityDoc(entity: String): Document? {
        //            memoizedAbstractDocs.computeIfAbsent(entity, {key ->
        val nameQuery = buildEntityNameQuery(entity)
        val searchResult = indexSearcher.search(nameQuery, 1)
        val result =    if (searchResult.scoreDocs.isEmpty()) null
                        else indexSearcher.doc(searchResult.scoreDocs[0].doc)
        return result
    }

    fun buildEntityNameQuery(entity: String): BooleanQuery =
            BooleanQuery.Builder()
                .apply { add(FuzzyQuery(Term("name", entity)), BooleanClause.Occur.SHOULD) }
                .build()


    fun getRelevantEntities(query: String): List<RelevantEntity> {
        val cleanedQuery = AnalyzerFunctions
            .createTokenList(query, useFiltering = true)
            .joinToString(" ")
        val booleanQuery = AnalyzerFunctions.createQuery(query, useFiltering = true)

        val tops = gramAnalyzer.indexSearcher.search(booleanQuery, 20)
        val numHits = tops.scoreDocs.size.toDouble()
        val queryModel = gramAnalyzer.getCorpusStatContainer(cleanedQuery)

        return tops.scoreDocs
            .map { scoreDoc -> gramAnalyzer.indexSearcher.doc(scoreDoc.doc) to scoreDoc.score.toDouble()}
            .sortedByDescending { pair -> pair.second }
            .mapIndexed { index, (doc, score) ->
                val name = doc.get("name")
                val content = doc.get("content")
                val stat = gramAnalyzer.getCorpusStatContainer(content)
                val tempContainer = LanguageStatContainer(unigramStat = stat.unigramStat.corpusDoc,
                                                        bigramStat = stat.bigramStat.corpusDoc,
                                                        bigramWindowStat = stat.bigramWindowStat.corpusDoc)

                // Crappy workaround... should have derived these classes instead
                val likelihood = tempContainer.getLikelihoodGivenQuery(queryModel)
                RelevantEntity(name = name,
                        content = content,
                        statContainer = stat,
                        score = score,
                        rank = (numHits - index) / numHits,
                        queryLikelihood = likelihood)}
    }

    fun getClosestRelevantEntity(entity: String, rels: List<RelevantEntity>): Pair<Double, RelevantEntity>? =
        rels.map { relevantEntity -> sim.distance(entity, relevantEntity.name) to relevantEntity  }
            .maxBy { (similarity, _) -> similarity }
            ?.let { (similarity, relevantEntity) ->
                if (similarity < 0.9) null
                else Pair(similarity, relevantEntity)
            }

    fun retrieveTermStats(term: String): Long =
        indexSearcher.indexReader.totalTermFreq(Term("text", term))

    fun getEntityTokens(entity: String): List<String>? {
        val cleanName = entity.toLowerCase().replace(" ", "_")
        val query = BooleanQuery
            .Builder()
            .apply { add(FuzzyQuery(Term("name", cleanName)), BooleanClause.Occur.SHOULD) }
            .build()

        val topDocs = indexSearcher.search(query, 1)
        if (topDocs.scoreDocs.isEmpty()) {
            return null
        }

        val entityDoc = indexSearcher.doc(topDocs.scoreDocs[0].doc)
        val content = entityDoc.get("text")
//        return createTokenSequence(content).toList()
        return AnalyzerFunctions.createTokenList(content)
    }

    fun getTermStats(terms: List<String>): List<Pair<String, Double>> {
        val totalTerms = indexSearcher.indexReader.getSumTotalTermFreq("text").toDouble()
        return terms.map { term ->
            val termFreq = indexSearcher.indexReader.totalTermFreq(Term("text", term)) / totalTerms
            term to termFreq
        }.toList()
    }



    fun runTest() {

    }

}


