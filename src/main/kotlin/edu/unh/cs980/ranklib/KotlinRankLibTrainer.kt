@file:JvmName("KotRankLibTrainer")
package edu.unh.cs980.ranklib

import edu.unh.cs980.CONTENT
import edu.unh.cs980.KotlinDatabase
import edu.unh.cs980.KotlinGraphAnalyzer
import edu.unh.cs980.context.HyperlinkIndexer
import edu.unh.cs980.features.*
import edu.unh.cs980.getIndexSearcher
import edu.unh.cs980.language.GramStatType
import edu.unh.cs980.language.KotlinAbstractAnalyzer
import edu.unh.cs980.language.KotlinGramAnalyzer
import edu.unh.cs980.misc.AnalyzerFunctions
import info.debatty.java.stringsimilarity.Jaccard
import info.debatty.java.stringsimilarity.JaroWinkler
import info.debatty.java.stringsimilarity.NormalizedLevenshtein
import info.debatty.java.stringsimilarity.SorensenDice
import info.debatty.java.stringsimilarity.interfaces.StringDistance
import org.apache.lucene.index.Term
import org.apache.lucene.search.*
import org.apache.lucene.search.similarities.*
import java.lang.Double.sum
import java.util.*


/**
 * Function: KotlinRankLibTrainer
 * Description: This is used to encapsulate my different query methods, and the training methods I used to
 *              learn their weights.
 */
class KotlinRankLibTrainer(indexPath: String, queryPath: String, qrelPath: String, graphPath: String) {

    val db = if (graphPath == "") null else KotlinDatabase(graphPath)
    val formatter = KotlinRanklibFormatter(queryPath, qrelPath, indexPath)
    val graphAnalyzer = if (graphPath == "") null else KotlinGraphAnalyzer(formatter.indexSearcher, db!!)



    /**
     * Function: queryCombined
     * Description: Score with weighted combination of BM25, Jaccard string similarity, LM_Dirichlet, and second/third
     *              section headers (trained using RankLib).
     */
    private fun queryCombined() {
//        val weights = listOf(0.3106317698753524,-0.025891305471130843,
//                0.34751201103557083, -0.2358113441529167, -0.08015356975284649)

        val weights = listOf(0.27711892, 0.04586862, 0.24996234, -0.21980639, -0.100580536, 0.008560385,0.09810279)

        formatter.addBM25(weight = weights[0], normType = NormType.ZSCORE)

        formatter.addFeature({ query, tops, indexSearcher ->
            featAddStringDistanceFunction(query, tops, indexSearcher, Jaccard() )
        }, weight = weights[1], normType = NormType.ZSCORE)

        formatter.addFeature({query, tops, indexSearcher ->
            featUseLucSim(query, tops, indexSearcher, LMDirichletSimilarity())
        }, weight = weights[2],
                normType = NormType.ZSCORE)

        formatter.addFeature({ query, tops, indexSearcher ->
            featSectionSplit(query, tops, indexSearcher, 1) }, weight = weights[3], normType = NormType.ZSCORE)

        formatter.addFeature({ query, tops, indexSearcher ->
            featSectionSplit(query, tops, indexSearcher, 2) }, weight = weights[4], normType = NormType.ZSCORE)

        val gramIndexSearcher = getIndexSearcher("gram")
        val hLinker = HyperlinkIndexer("entity_mentions.db")
        val hGram = KotlinGramAnalyzer(gramIndexSearcher)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSDM(query, tops, indexSearcher, hGram, 0.5)
        }, normType = NormType.ZSCORE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featLikehoodOfQueryGivenEntityMention(query, tops, indexSearcher, hLinker)}, normType = NormType.ZSCORE)
    }

    private fun queryAbstractSim() {
        val abstractSearcher = getIndexSearcher("abstract")
//        formatter.addFeature(::featSectionComponent, normType = NormType.ZSCORE, weight = 0.8345728173873589)
        formatter.addFeature(::featSectionComponent, normType = NormType.ZSCORE, weight = 1.0)
//        formatter.addFeature({ query, tops, indexSearcher ->
//            featAbstractSim(query, tops, indexSearcher, abstractSearcher, BM25Similarity())}, normType = NormType.ZSCORE,
//                weight = 0.16542718261264)
    }

    private fun querySectionPath() {
        val weights = listOf(0.200983, 0.099785, 0.223777, 0.4754529531)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSectionSplit(query, tops, indexSearcher, 0) }, normType = NormType.NONE, weight = weights[0])
        formatter.addFeature({ query, tops, indexSearcher ->
            featSectionSplit(query, tops, indexSearcher, 1) }, normType = NormType.NONE, weight = weights[1])
        formatter.addFeature({ query, tops, indexSearcher ->
            featSectionSplit(query, tops, indexSearcher, 2) }, normType = NormType.NONE, weight = weights[2])
        formatter.addFeature({ query, tops, indexSearcher ->
            featSectionSplit(query, tops, indexSearcher, 3) }, normType = NormType.NONE, weight = weights[3])

    }

    private fun queryAbstract() {
        val weights = listOf(0.49827237108, 0.23021207089, 0.1280351944, 0.143480363604666)
        formatter.addFeature(::featSectionComponent, normType = NormType.ZSCORE, weight = weights[0])
        val gramSearcher = getIndexSearcher("gram")
//        formatter.addBM25(normType = NormType.ZSCORE, weight = 1.0)
        val hGram = KotlinGramAnalyzer(gramSearcher)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSDM(query, tops, indexSearcher, hGram, 4.0)
        }, normType = NormType.ZSCORE, weight = weights[1])

//        val hLinker = HyperlinkIndexer("entity_mentions.db")
//        formatter.addFeature({ query, tops, indexSearcher ->
//            featLikehoodOfQueryGivenEntityMention(query, tops, indexSearcher, hLinker)}, normType = NormType.ZSCORE,
//                weight = 0.176)
        formatter.addFeature(::featStringSimilarityComponent, normType = NormType.ZSCORE)

//        formatter.addFeature({ query, tops, indexSearcher ->
//            featAddStringDistanceFunction(query, tops, indexSearcher, Jaccard() )
//        }, normType = NormType.ZSCORE, weight = weights[2])
//        formatter.addFeature({query, tops, indexSearcher ->
//            featUseLucSim(query, tops, indexSearcher, LMDirichletSimilarity())
//        }, normType = NormType.ZSCORE, weight = weights[3])
    }

    private fun querySDMComponents() {
        formatter.addBM25(normType = NormType.ZSCORE, weight = 0.381845239)
        val gramSearcher = getIndexSearcher("gram")
        val hGram = KotlinGramAnalyzer(gramSearcher)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSDM(query, tops, indexSearcher, hGram, 4.0, GramStatType.TYPE_UNIGRAM)
        }, normType = NormType.ZSCORE, weight = -0.385628901)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSDM(query, tops, indexSearcher, hGram, 4.0, GramStatType.TYPE_BIGRAM)
        }, normType = NormType.ZSCORE, weight = 0.01499519)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSDM(query, tops, indexSearcher, hGram, 4.0, GramStatType.TYPE_BIGRAM_WINDOW)
        }, normType = NormType.ZSCORE, weight = 0.217530662)
    }

    // Runs associated query method
    fun runRanklibQuery(method: String, out: String) {
        when (method) {
            "abstract_score" -> queryAbstract()
            "sdm_components" -> querySDMComponents()
            "abstract_sim" -> queryAbstractSim()
            "section_path" -> querySectionPath()
            "combined" -> queryCombined()
            else -> println("Unknown method!")
        }

        // After scoring according to method, rerank the queries and write them to a run file
        formatter.rerankQueries()
        formatter.queryRetriever.writeQueriesToFile(formatter.queries, out)
    }




    /**
     * Function: trainCombined
     * Description: training for combined method.
     * @see queryCombined
     */
    private fun trainCombined() {
        formatter.addBM25(weight = 1.0, normType = NormType.ZSCORE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featAddStringDistanceFunction(query, tops, indexSearcher, Jaccard() )
        }, normType = NormType.ZSCORE)
        formatter.addFeature({query, tops, indexSearcher ->
            featUseLucSim(query, tops, indexSearcher, LMDirichletSimilarity())
        }, normType = NormType.ZSCORE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSectionSplit(query, tops, indexSearcher, 1) }, normType = NormType.ZSCORE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSectionSplit(query, tops, indexSearcher, 2) }, normType = NormType.ZSCORE)

        val gramIndexSearcher = getIndexSearcher("gram")
        val hLinker = HyperlinkIndexer("entity_mentions.db")
        val hGram = KotlinGramAnalyzer(gramIndexSearcher)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSDM(query, tops, indexSearcher, hGram, 0.5)
        }, normType = NormType.ZSCORE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featLikehoodOfQueryGivenEntityMention(query, tops, indexSearcher, hLinker)}, normType = NormType.ZSCORE)

        //[(1, 0.37643456), (2, 0.06951797), (3, 0.21233706), (4, -0.11022807), (5, -0.03575336), (6, -0.06678948), (7, 0.12893948)]
        //[(1, 0.27711892), (2, 0.04586862), (3, 0.24996234), (4, -0.21980639), (5, -0.100580536), (6, 0.008560385), (7, 0.09810279)]
    }


    private fun trainDirichletAlpha() {
        formatter.addBM25(normType = NormType.ZSCORE)
        val gramIndexSearcher = getIndexSearcher("gram")
        val hGram = KotlinGramAnalyzer(gramIndexSearcher)
        listOf(2, 8, 16, 32, 64, 128).forEach { alpha ->
            formatter.addFeature({ query, tops, indexSearcher ->
                featSDM(query, tops, indexSearcher, hGram, alpha.toDouble())
            }, normType = NormType.ZSCORE)
        }

    }

    private fun trainSDMComponents() {
        val gramIndexSearcher = getIndexSearcher("gram")
        val hGram = KotlinGramAnalyzer(gramIndexSearcher)
        val grams = listOf(GramStatType.TYPE_UNIGRAM, GramStatType.TYPE_BIGRAM, GramStatType.TYPE_BIGRAM_WINDOW)
        grams.forEach { gram ->
            formatter.addFeature({ query, tops, indexSearcher ->
                featSDM(query, tops, indexSearcher, hGram, 4.0, gramType = gram)
            }, normType = NormType.NONE)
        }
    }

    private fun trainEntitySDMComponents() {
        val abstractSearcher = getIndexSearcher("abstract")
        val abstractAnalyzer = KotlinAbstractAnalyzer(abstractSearcher)
        val grams = listOf(GramStatType.TYPE_UNIGRAM, GramStatType.TYPE_BIGRAM, GramStatType.TYPE_BIGRAM_WINDOW)
        grams.forEach { gram ->
            formatter.addFeature({ query, tops, indexSearcher ->
                featEntitySDM(query, tops, indexSearcher, abstractAnalyzer, gramType = gram)
            }, normType = NormType.NONE)
        }
    }

    private fun trainAbstractSDM() {
        formatter.addBM25(normType = NormType.ZSCORE)
        val abstractIndexer = getIndexSearcher("abstract")
        val abstractAnalyzer = KotlinAbstractAnalyzer(abstractIndexer)
        formatter.addFeature({ query, tops, indexSearcher ->
            featEntitySDM(query, tops, indexSearcher, abstractAnalyzer)
        }, normType = NormType.ZSCORE)


    }

    private fun trainSectionPath() {
        formatter.addFeature({ query, tops, indexSearcher ->
            featSectionSplit(query, tops, indexSearcher, 0) }, normType = NormType.NONE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSectionSplit(query, tops, indexSearcher, 1) }, normType = NormType.NONE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSectionSplit(query, tops, indexSearcher, 2) }, normType = NormType.NONE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSectionSplit(query, tops, indexSearcher, 3) }, normType = NormType.NONE)
    }

    private fun trainSimilarityComponents() {
        formatter.addFeature({ query, tops, indexSearcher ->
            featAddStringDistanceFunction(query, tops, indexSearcher, Jaccard() )
        }, normType = NormType.NONE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featAddStringDistanceFunction(query, tops, indexSearcher, JaroWinkler() )
        }, normType = NormType.NONE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featAddStringDistanceFunction(query, tops, indexSearcher, NormalizedLevenshtein() )
        }, normType = NormType.NONE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featAddStringDistanceFunction(query, tops, indexSearcher, SorensenDice() )
        }, normType = NormType.NONE)
    }

    private fun trainSimilaritySection() {
        formatter.addFeature({ query, tops, indexSearcher ->
            featSplitSim(query, tops, indexSearcher, ::featStringSimilarityComponent,
                    secWeights = listOf(1.0, 0.0, 0.0, 0.0))}, normType = NormType.NONE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSplitSim(query, tops, indexSearcher, ::featStringSimilarityComponent,
                    secWeights = listOf(0.0, 1.0, 0.0, 0.0))}, normType = NormType.NONE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSplitSim(query, tops, indexSearcher, ::featStringSimilarityComponent,
                    secWeights = listOf(0.0, 0.0, 1.0, 0.0))}, normType = NormType.NONE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSplitSim(query, tops, indexSearcher, ::featStringSimilarityComponent,
                    secWeights = listOf(0.0, 0.0, 0.0, 1.0))}, normType = NormType.NONE)

//        val weights = listOf(0.13506566, -0.49940691, 0.21757824, 0.14794917259)
    }

    private fun trainAbstractScore() {
//        formatter.addBM25(normType = NormType.ZSCORE)
        formatter.addFeature(::featSectionComponent, normType = NormType.ZSCORE)
        val gramIndexSearcher = getIndexSearcher("gram")
//        val hLinker = HyperlinkIndexer("entity_mentions.db")
        val hGram = KotlinGramAnalyzer(gramIndexSearcher)
        formatter.addFeature({ query, tops, indexSearcher ->
            featSDM(query, tops, indexSearcher, hGram, 4.0)
        }, normType = NormType.ZSCORE)

        formatter.addFeature(::featStringSimilarityComponent, normType = NormType.ZSCORE)
    }

    private fun trainAbstractSim() {
        val abstractSearcher = getIndexSearcher("abstract")
        formatter.addFeature(::featSectionComponent, normType = NormType.ZSCORE)
        formatter.addFeature({ query, tops, indexSearcher ->
            featAbstractSim(query, tops, indexSearcher, abstractSearcher, BM25Similarity())}, normType = NormType.ZSCORE)
    }

    /**
     * Function: train
     * Description: Add features associated with training method and then writes scored features to a RankLib compatible
     *              file for later use in training weights.
     */
    fun train(method: String, out: String) {
        when (method) {
            "abstract_score" -> trainAbstractScore()
            "abstract_sdm" -> trainAbstractSDM()
            "abstract_sim" -> trainAbstractSim()
            "train_alpha" -> trainDirichletAlpha()
            "section_path" -> trainSectionPath()
            "train_sdm_components" -> trainSDMComponents()
            "string_similarity_components" -> trainSimilarityComponents()
            "similarity_section" -> trainSimilaritySection()
            "train_entity_sdm_components" -> trainEntitySDMComponents()
            "combined" -> trainCombined()
            else -> println("Unknown method!")
        }
        formatter.writeToRankLibFile(out)
    }
}
