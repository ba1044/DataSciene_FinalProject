package edu.unh.cs980.paragraph

import edu.unh.cs980.CONTENT
import edu.unh.cs980.WordEmbedding.ResultQuery
import edu.unh.cs980.getIndexSearcher
import edu.unh.cs980.language.KernelDist
import edu.unh.cs980.language.KotlinGramAnalyzer
import edu.unh.cs980.language.KotlinKernelAnalyzer
import edu.unh.cs980.language.TopicMixtureResult
import edu.unh.cs980.misc.AnalyzerFunctions
import edu.unh.cs980.withTime
import org.apache.lucene.index.Term
import java.io.File
import java.lang.Double.sum
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis


class KotlinEmbedding(indexLoc: String) {
//    private val gramAnalyzer = KotlinGramAnalyzer(gramLoc)
    private val totalFreqs = HashMap<String, Double>()
    private val searcher = getIndexSearcher(indexLoc)
    private val memoizedFreqs = ConcurrentHashMap<String, Double>()
    private val kernelAnalyzer =
//            KotlinKernelAnalyzer(0.0, 1.0, corpus = {s -> totalFreqs[s]}, partitioned = true)
                KotlinKernelAnalyzer(0.0, 1.0, corpus = {null}, partitioned = true)


    var topicTime: Long = 0

//    private val totalCorpusFreq = gramAnalyzer.indexSearcher.indexReader
//        .getSumTotalTermFreq("unigram")
//        .toDouble()

    fun query(query: String, nQueries: Int = 10): List<String> {
        val boolQuery = AnalyzerFunctions.createQuery(query)
        val results = searcher.search(boolQuery, nQueries)
        return results.scoreDocs.map { scoreDoc ->
            val doc = searcher.doc(scoreDoc.doc)
            doc.get(CONTENT)
        }
    }

    fun scoreResults(combined: TopicMixtureResult, docs: List<Pair<String, TopicMixtureResult>>) {
        docs.map { doc -> doc to doc.second.deltaSim(combined) }
            .sortedBy { (_, distance) -> distance }
            .forEach { (doc, distance) -> println("${doc.first}:\n\t $distance") }

    }

    fun reportQueryResults(queryString: String, smoothDocs: Boolean = false, smoothCombined: Boolean = false) {
        val docs = query(queryString)

        val total = docs.joinToString("\n")
        val results = ArrayList<Pair<String, TopicMixtureResult>>()

        docs.forEach { doc ->
            val result = embed(doc, nSamples = 1000, nIterations = 400, smooth = smoothDocs)
            result.reportResults()
            results += doc to result
            println(doc + "\n\n")
        }

        val otherText = """
            During Microsoft's early history, Gates was an active software developer, particularly in the company's programming language products, but his basic role in most of the company's history was primarily as a manager and executive. Gates has not officially been on a development team since working on the TRS-80 Model 100,[65] but as late as 1989 he wrote code that shipped with the company's products.[63] He remained interested in technical details; in 1985, Jerry Pournelle wrote that when he watched Gates announce Microsoft Excel, "Something else impressed me. Bill Gates likes the program, not because it's going to make him a lot of money (although I'm sure it will do that), but because it's a neat hack."[66]
            """
        results += otherText to embed(otherText, nSamples = 1000, nIterations = 400, smooth = smoothDocs)

        println("COMBINED")
        val result = embed(total, nSamples = 1000, nIterations = 400, smooth = smoothCombined)
        result.reportResults()
        println("\n\n")

        println("QUERY")
        embed(queryString, nSamples = 1000, nIterations = 400, smooth = false).reportResults()
        println("\n\n")
        scoreResults(result, results)
    }


    fun loadTopics(directory: String, filterList: List<String> = listOf(), smooth: Boolean = false) {
        val time = measureTimeMillis {
            kernelAnalyzer.analyzeTopicDirectories(directory, filterList, smooth)
            kernelAnalyzer.normalizeTopics()
            if (smooth) {
                kernelAnalyzer.topics.values.forEach { it.equilibriumCovariance() }
            }

            kernelAnalyzer.topics.forEach { topic ->
                topic.value.getKernelFreqs().forEach { (k,v) -> totalFreqs.merge(k, v, ::sum)}
            }

            val total = totalFreqs.values.sum()
            totalFreqs.forEach { k, v -> totalFreqs[k] = v/total  }
        }
        topicTime = time
    }

    fun loadQueries(queryString: String, filterList: List<String> = listOf(), smooth: Boolean = false, nQueries: Int = 5) {
        val results = query(queryString, nQueries)
        results.mapIndexed { index, s -> kernelAnalyzer.createTopicFromParagraph("$index", s, smooth)  }
        kernelAnalyzer.normalizeTopics()
    }

    fun expandQueryText(queryString: String, nQueries: Int = 5) =
        AnalyzerFunctions.createTokenList(queryString)
            .map { query(it, nQueries).joinToString("\n") }
            .joinToString()

    private fun runEmbedding(kernelDist: KernelDist, text: String, nSamples: Int = 300, nIterations: Int = 500,
                             smooth: Boolean = false): Triple<List<String>, List<Double>, Double> {

        val identityFreqs = "identity" to kernelDist.getKernelFreqs()


        val samples = kernelDist.perturb(nSamples)

        val topicStats = kernelAnalyzer.retrieveTopicFrequencies() + identityFreqs
        val stochasticIntegrator = KotlinStochasticIntegrator(samples, topicStats, corpus = {_ -> null})
        val integrals = stochasticIntegrator.integrate()

        return kernelAnalyzer.classifyByDomainSimplex2(integrals, nIterations = nIterations,smooth = false)
    }

    fun embed(text: String, nSamples: Int = 300, nIterations: Int = 500, smooth: Boolean = false): TopicMixtureResult {
        val kernelDist = KernelDist(0.0, 20.0)
            .apply { analyzePartitionedDocument(text) }
            .apply { normalizeKernels() }
        if (smooth) {
            kernelDist.equilibriumCovariance()
        }


        val nTimes = 1
        val (names, weights, kld) =
                (0 until nTimes)
                    .map { runEmbedding(kernelDist, text, nSamples, nIterations, smooth) }
                    .reduce { (names, accWeights, accKld), (_, nextWeights, nextKld) ->
                        val newWeights = accWeights.zip(nextWeights).map { (w1, w2) -> w1 + w2 }
                        Triple(names, newWeights, accKld + nextKld) }
                    .let { (names, weights, kld) ->
                        val finalWeights = weights.map { weight -> weight / nTimes.toDouble() }
                        Triple(names, finalWeights, kld / nTimes)}


        val results = names.zip(weights).toMap()
        return TopicMixtureResult(results.toSortedMap(), kld)

//        val identityFreqs = "identity" to kernelDist.getKernelFreqs()

//        if (smooth) { kernelDist.equilibriumCovariance() }
//
//        val samples = kernelDist.perturb(nSamples)
//
//        val topicStats = kernelAnalyzer.retrieveTopicFrequencies() + identityFreqs
//        val stochasticIntegrator = KotlinStochasticIntegrator(samples, topicStats, corpus = {_ -> null})
//        val integrals = stochasticIntegrator.integrate()
//
//        return kernelAnalyzer.classifyByDomainSimplex2(integrals, nIterations = nIterations,smooth = false)
    }
}


