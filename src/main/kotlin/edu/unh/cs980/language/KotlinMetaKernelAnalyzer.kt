package edu.unh.cs980.language

import edu.unh.cs980.*
import edu.unh.cs980.misc.AnalyzerFunctions
import edu.unh.cs980.misc.GradientDescenter
import edu.unh.cs980.misc.PartitionDescenter
import edu.unh.cs980.paragraph.KotlinStochasticIntegrator
import info.debatty.java.stringsimilarity.*
import org.apache.commons.math3.distribution.NormalDistribution
import org.apache.lucene.analysis.en.EnglishAnalyzer
import java.io.*
import java.lang.Integer.max
import java.lang.Math.pow

enum class ReductionMethod {
    REDUCTION_MAX_MAX, REDUCTION_AVERAGE, REDUCTION_MAX_AVERAGE
}

data class DescentData(val simFun: (String) -> Map<String, Double>,
                       val partitionFun: ((String) -> List<String>)? = null)

//class Sheaf(val name: String, val text: String, val cover: Sheaf? = null, useLetterGram: Boolean = false) {
class Sheaf(val name: String, val partitions: List<String>, val kld: Double = 1.0, val cover: Sheaf? = null) : Serializable {
    val measure = HashMap<String, Pair<Sheaf, Double>>()

    fun descend(descentData: List<DescentData>)  {
        val (simFun, partitionFun) = descentData.firstOrNull() ?: return
        val leftovers = descentData.subList(1, descentData.size)

        if (partitions.isEmpty()) {
            println("There's a hole in $name")
            println(cover!!.partitions)
            return
        }

        val (partitionSims, partitionTexts) = partitions.mapIndexed { index, text ->
            val pname = "${name}_$index"
            (pname to simFun(text)) to (pname to text) }.unzip()

        val coveringSim = partitionSims.flatMap { partition -> partition.second.entries }
            .groupingBy { it.key }
            .fold(0.0) { acc, entry -> acc!! + entry.value }
            .normalize()


        val (featureNames, weights) = (0 until 3).map {
            doPerturb(coveringSim, partitionSims = partitionSims)}
            .reduce { acc, list -> acc.zip(list).map { (f1, f2) -> f1.first to f2.second + f1.second } }
            .map { it.first to it.second / 3 }.unzip()

        val results = featureNames.zip(weights).toMap()
        val partitionTextMap = partitionTexts.toMap()


        val mixture =  TopicMixtureResult(results.toSortedMap(), kld)
        mixture.reportResults()

        partitionSims.mapNotNull { (name, _) -> results[name]?.to(name) }
            .filter { it.first > 0.0 }
            .map { (freq, sheafName) ->
                val partitionText = partitionTextMap[sheafName]!!
                val newPartitions = partitionFun?.invoke(partitionText) ?: emptyList()
                if (newPartitions.isEmpty()) { println("$sheafName") }
                measure[sheafName] = Sheaf(sheafName, newPartitions, kld, this) to freq
            }

        measure.forEach { (_, sheafMeasure) ->
            val sheaf = sheafMeasure.first
            sheaf.descend(leftovers)
        }

    }

    fun doPerturb(coveringSim: Map<String, Double>, partitionSims: List<Pair<String, Map<String, Double>>>): List<Pair<String, Double>> {
        val perturbations = perturb(100, coveringSim)
        val integrator = KotlinStochasticIntegrator(perturbations, partitionSims + (name to coveringSim), {null}, false)
        val integrals = integrator.integrate()

        val identityFreq = integrals.find { it.first == name }!!.second
        val (featureNames, featureFreqs) = integrals.filter { it.first != name }.unzip()

//        val stepper = PartitionDescenter(identityFreq, featureFreqs)
        val stepper = GradientDescenter(identityFreq, featureFreqs)
        val (weights, kld) = stepper.startDescent(800)
        return featureNames.zip(weights)
    }

    fun transferMeasure(simFun: (String) -> Double): Pair<String, Double> {
        val similarityMeasure =
                partitions.map(simFun).average().defaultWhenNotFinite(0.0)
        return cover!!.ascend(name, similarityMeasure)
    }

    fun ascend(datumName: String, simMeasure: Double): Pair<String, Double> {
        val adjustedMeasure = measure[datumName]!!.second * simMeasure
        return cover?.ascend(name, adjustedMeasure) ?: name to adjustedMeasure
    }

    fun measurePartitions(simFun: (String) -> Double): Double  =
            partitions.map { partition -> simFun(partition) }.average()!!.defaultWhenNotFinite(0.00)

    fun transferDown(depthToGo: Int, simFun: (String) -> Double): Double {
        if (depthToGo == 0) return measurePartitions(simFun)


        val total = measure.values
            .sumByDouble { (sheaf, freq) ->
                sheaf.transferDown(depthToGo - 1, simFun) * freq
            }
//        println("$name: $total")
        if (total < 1/max(1, partitions.size).toDouble()) return 0.0 else return total

//        return total
    }


    fun retrieveLayer(depth: Int): List<Sheaf> =
        if (depth == 0) listOf(this)
        else measure.values.flatMap { (sheaf, _) -> sheaf.retrieveLayer(depth - 1) }

    companion object {
        fun perturb(nSamples: Int = 50, sims: Map<String, Double>): Pair<List<String>, List<List<Double>>> {
            val norm = NormalDistribution(sharedRand, 1.0, 0.001)
            val (kernelNames, kernelFreqs) = sims.toList().unzip()

            val perturbations = (0 until nSamples).map {
                norm.sample(kernelFreqs.size)
                    .toList()
                    .zip(kernelFreqs)
                    .map { (gaussian, kernelFreq) -> gaussian + kernelFreq  }.normalize() }

            return kernelNames to perturbations
        }

    }
}


class KotlinMetaKernelAnalyzer(val paragraphIndex: String) {
    val sheaves = arrayListOf<Sheaf>()
    private val sim = NormalizedLevenshtein()
//    private val sim = Jaccard(4)

    fun unigramFreq(text: String): Map<String, Double> =
        AnalyzerFunctions.createTokenList(text, analyzerType = AnalyzerFunctions.AnalyzerType.ANALYZER_ENGLISH)
            .groupingBy(::identity)
            .eachCount()
            .normalize()

    fun bigramFrequency(text: String): Map<String, Double> =
            AnalyzerFunctions.createTokenList(text, analyzerType = AnalyzerFunctions.AnalyzerType.ANALYZER_ENGLISH_STOPPED)
                .windowed(2, partialWindows = false)
                .flatMap { (first, second) -> listOf("${first}_$second", "${second}_$first") }
                .groupingBy(::identity)
                .eachCount()
                .normalize()

    fun splitSentence(text: String): List<String> = text.split(".")
        .filter { it.length > 2 }

    fun splitWord(text: String): List<String> =
//            AnalyzerFunctions.createTokenList(text, analyzerType = AnalyzerFunctions.AnalyzerType.ANALYZER_ENGLISH)
            "[ ]".toRegex().split(text)
                .filter { it.length > 2 }
                .toSet()
                .toList()


    fun letterFreq(windowSize: Int, text: String, partial: Boolean) =
        AnalyzerFunctions.createTokenList(text, analyzerType = AnalyzerFunctions.AnalyzerType.ANALYZER_ENGLISH_STOPPED)
            .flatMap { token -> token.windowed(windowSize, partialWindows = partial) }
            .groupingBy(::identity)
            .eachCount()
            .normalize()

    fun letterFreq2(windowSize: Int, text: String, partial: Boolean) =
            AnalyzerFunctions.createTokenList(text, analyzerType = AnalyzerFunctions.AnalyzerType.ANALYZER_ENGLISH_STOPPED)
                .flatMap { token -> token.windowed(windowSize, partialWindows = partial).flatMap { window -> listOf(window, window.reversed())} }
                .groupingBy(::identity)
                .eachCount()
                .normalize()

    fun bindFreq(windowSize: Int, partial: Boolean = false) = { text: String -> letterFreq(windowSize, text, partial)}

    fun singleLetterFreq(text: String) =
            text.map(Char::toString)
                .groupingBy(::identity)
                .eachCount()
                .normalize()

    fun evaluateMeasure(startingLayer: Int, measureLayer: Int, measure: (String) -> Double) =
            extractSheaves(startingLayer)
                .flatMap { (topName, sheafLayer) ->
                    sheafLayer.map { sheaf ->
                        sheaf.name to sheaf.transferDown(measureLayer - startingLayer, measure) } }



    private fun trainParagraph(topic: String, directory: File) {
        val paragraphs = directory.listFiles().map { file -> file.readText().toLowerCase().replace(",", " ") }
            .map { text -> splitSentence(text).map {  text ->
                AnalyzerFunctions.createTokenList(text, analyzerType = AnalyzerFunctions.AnalyzerType.ANALYZER_ENGLISH_STOPPED).joinToString(" ") }
                .joinToString(". ")
            }

        val sheaf = Sheaf(topic, paragraphs)
        val descentData = listOf(
//                DescentData(this::unigramFreq, this::splitSentence),
                DescentData(bindFreq(2), this::splitSentence),
                DescentData(bindFreq(2), this::splitWord),
                DescentData(bindFreq(2), ::listOf)
//                DescentData(this::unigramFreq, ::listOf)
//                DescentData(bindFreq(4), this::splitSentence),
//                DescentData(bindFreq(4), this::splitWord),
//                DescentData(bindFreq(4), ::listOf)
//                        DescentData(bindFreq(3), this::splitSentence),
//        DescentData(bindFreq(2), this::splitWord),
//        DescentData(bindFreq(1), ::listOf)
        )
        sheaf.descend(descentData)
        File("descent_data/").let { file -> if (!file.exists()) file.mkdir() }
        val f = FileOutputStream("descent_data/$topic")
        val of = ObjectOutputStream(f)
        of.writeObject(sheaf)
        of.close()
        f.close()


    }

    fun extractSheaves(level: Int) =
        sheaves.map { sheaf -> sheaf.name to sheaf.retrieveLayer(level) }

    fun trainParagraphs(filterList: List<String> = emptyList()) {
        File(paragraphIndex)
            .listFiles()
            .filter { file -> file.isDirectory && (filterList.isEmpty() || file.name in filterList)  }
            .forEach { file -> trainParagraph(file.name, file) }
    }

    fun extractTopicText(topicDir: File): List<String> =
        topicDir.listFiles().map { file -> file.readText().toLowerCase().replace(",", " ") }
            .map { text -> splitSentence(text).map {  text ->
            AnalyzerFunctions.createTokenList(text, analyzerType = AnalyzerFunctions.AnalyzerType.ANALYZER_ENGLISH_STOPPED).joinToString(" ") }
            .joinToString(". ") }


//    fun combinedTraining(filterList: List<String> = emptyList()) {
//        val allParas = File(paragraphIndex)
//            .listFiles()
//            .filter { file -> filterList.isEmpty() || file.name in filterList }
//            .flatMap { file -> extractTopicText(file) }
//
//        val sheaf = Sheaf("Combined", allParas)
//        val descentData = listOf(
//                DescentData(this::unigramFreq, this::splitSentence),
//                DescentData(bindFreq(3), this::splitWord),
////                DescentData(bindFreq(2), this::splitWord),
//                DescentData(bindFreq(2), ::listOf)
////                DescentData(this::singleLetterFreq, ::listOf)
////                DescentData(bindFreq(1), ::listOf)
//        )
//        sheaf.descend(descentData)
//        File("descent_data/").let { file -> if (!file.exists()) file.mkdir() }
//        val f = FileOutputStream("descent_data/Combined")
//        val of = ObjectOutputStream(f)
//        of.writeObject(sheaf)
//        of.close()
//        f.close()
//    }

    fun loadSheaves(sheafIndex: String, filterWords: List<String> = emptyList()) =
            File(sheafIndex)
                .listFiles()
                .filter { file -> filterWords.isEmpty() || file.name in filterWords }
                .map { file ->
                    val reader = ObjectInputStream(FileInputStream(file))
                    reader.readObject() as Sheaf}
                .onEach { sheaf -> sheaves += sheaf }


    fun inferMetric(text: String, startingLayer: Int, measureLayer: Int,
                    doNormalize: Boolean = true,
                    reductionMethod: ReductionMethod = ReductionMethod.REDUCTION_MAX_AVERAGE): TopicMixtureResult {

        val mySentence = bindSims(text, reductionMethod = reductionMethod)
        val res = evaluateMeasure(startingLayer, measureLayer, mySentence)
            .toMap()
            .run { if (doNormalize) normalize() else this }

        return TopicMixtureResult(res.toSortedMap(), 0.0)
    }

    fun averageSim(w1: String, w2: String): Double =
                (1.0 - sim.distance(w1, w2)).run { if (this < 0.8) 0.0 else 1.0 }
//                    .apply { if (this > 0.9) println("$w1: $w2: $this") }


    fun productMaxMax(w1: List<String>, w2: List<String>): Double =
            w1.map { word1 -> w2.map { word2 -> averageSim(word1, word2) }.max()!! }.max()!!

    fun productMaxAverage(w1: List<String>, w2: List<String>): Double {
        val results = w1.flatMap { word1 -> w2.map { word2 -> averageSim(word1, word2) } }
        val misses = results.count { it == 0.0 }
        val hits = results.sum()
//        return hits/pow(max(1, misses).toDouble(), 2.0)
//        return hits/max(1, misses)
        return results.sum()
//        return w1.flatMap { word1 -> w2.map { word2 -> averageSim(word1, word2) } }.sum()
    }

    fun productAverage(w1: List<String>, w2: List<String>): Double =
            w1.flatMap { word1 -> w2.map { word2 -> averageSim(word1, word2) } }.average()

    fun bindSims(text: String, reductionMethod: ReductionMethod): (String) -> Double {
        val w1 = filterWords(text)
        return { otherWords ->
            val target = filterWords(otherWords)
            when (reductionMethod) {
                ReductionMethod.REDUCTION_MAX_MAX     -> productMaxMax(w1, target)
                ReductionMethod.REDUCTION_AVERAGE     -> productAverage(w1, target)
                ReductionMethod.REDUCTION_MAX_AVERAGE -> productMaxAverage(w1, target)
            }
        }
    }

}


fun filterWords(text: String) =
    AnalyzerFunctions.createTokenList(text.toLowerCase(),
            analyzerType = AnalyzerFunctions.AnalyzerType.ANALYZER_ENGLISH_STOPPED)



fun testStuff2(metaAnalyzer: KotlinMetaKernelAnalyzer) {
//    val sheaves = metaAnalyzer.loadSheaves("descent_data/", filterWords = listOf("Medicine", "Cooking"))
    val sheaves = metaAnalyzer.loadSheaves("descent_data/")
    val text = """
        Instead of table service, there are food-serving counters/stalls, either in a line or allowing arbitrary walking paths. Customers take the food that they desire as they walk along, placing it on a tray. In addition, there are often stations where customers order food and wait while it is prepared, particularly for items such as hamburgers or tacos which must be served hot and can be immediately prepared. Alternatively, the patron is given a number and the item is brought to their table. For some food items and drinks, such as sodas, water, or the like, customers collect an empty container, pay at the check-out, and fill the container after the check-out. Free unlimited second servings are often allowed under this system. For legal purposes (and the consumption patterns of customers), this system is rarely, if at all, used for alcoholic beverages in the US.
            """

    val bb = """
        Nursing is a profession within the health care sector focused on the care of individuals, families, and communities so they may attain, maintain, or recover optimal health and quality of life. Nurses may be differentiated from other health care providers by their approach to patient care, training, and scope of practice. Nurses practice in many specialties with differing levels of prescription authority. Many nurses provide care within the ordering scope of physicians, and this traditional role has shaped the public image of nurses as care providers. However, nurse practitioners are permitted by most jurisdictions to practice independently in a variety of settings. In the postwar period, nurse education has undergone a process of diversification towards advanced and specialized credentials, and many of the traditional regulations and provider roles are changing.[1][2]

Nurses develop a plan of care, working collaboratively with physicians, therapists, the patient, the patient's family and other team members, that focuses on treating illness to improve quality of life. In the United States and the United Kingdom, advanced practice nurses, such as clinical nurse specialists and nurse practitioners, diagnose health problems and prescribe medications and other therapies, depending on individual state regulations. Nurses may help coordinate the patient care performed by other members of a multidisciplinary health care team such as therapists, medical practitioners and dietitians. Nurses provide care both interdependently, for example, with physicians, and independently as nursing professionals.
    """
    val red = ReductionMethod.REDUCTION_MAX_AVERAGE
    val result = metaAnalyzer.inferMetric(text, 0, 3, doNormalize = true, reductionMethod = red)
    val result2 = metaAnalyzer.inferMetric(bb, 0, 3, doNormalize = true, reductionMethod = red)
    result.reportResults()
    result2.reportResults()
//    result2.reportResults()
//    println(result.manhattenDistance(result2))

}

fun showSheaves(metaAnalyzer: KotlinMetaKernelAnalyzer) {
    val sheaves = metaAnalyzer.loadSheaves("descent_data/")
    val res = sheaves
        .filter { sheaf -> sheaf.name == "Cuisine" }
        .map { sheaf ->
        sheaf.retrieveLayer(3)
            .map { s ->
                val parentScore = s.cover?.run { measure[s.name]!!.second }.toString()
                s.partitions.joinToString(" ") + ":\n\t$parentScore\n"  }
            .joinToString("\n")
        }

    println(res)

}


fun exploreSheaves(metaAnalyzer: KotlinMetaKernelAnalyzer) {
    val sheaves = metaAnalyzer.loadSheaves("descent_data/")
}

fun buildStopWords() {
    val stops = EnglishAnalyzer.getDefaultStopSet()
    stops.addAll(listOf(
            "which", "this", "other", "than", "within"
    ))
}

fun main(args: Array<String>) {
    val metaAnalyzer = KotlinMetaKernelAnalyzer("paragraphs/")
//    metaAnalyzer.trainParagraphs()
//    metaAnalyzer.trainParagraphs(listOf("Cooking"))
//    metaAnalyzer.combinedTraining(listOf("Medicine", "Cooking", "Warfare"))
    testStuff2(metaAnalyzer)
//    showSheaves(metaAnalyzer)
//    println(metaAnalyzer.extractSheaves(1))
}