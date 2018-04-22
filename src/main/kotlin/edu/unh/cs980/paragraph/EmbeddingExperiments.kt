package edu.unh.cs980.paragraph

import edu.unh.cs980.language.TopicMixtureResult
import java.io.File

fun testQuery() {
//    embedder.reportQueryResults("Infinitesimal calculus", smoothDocs = false, smoothCombined = false)
//    embedder.reportQueryResults("Variational calculus and bayes theorem", smoothDocs = false, smoothCombined = false)

//    embedder.reportQueryResults("Health benefits of chocolate", smoothDocs = false, smoothCombined = false)

//    embedder.reportQueryResults("Microsoft", smoothDocs = false, smoothCombined = false)
//    embedder.reportQueryResults("Laplace operator", smoothDocs = false, smoothCombined = false)

}

fun testBasisParagraphs(embedder: KotlinEmbedding): TopicMixtureResult  {
    val testText =
            File("paragraphs/Biology/doc_1.txt").readText()
//                    File("paragraphs/Travel/doc_3.txt").readText()
//                    File("paragraphs/Games/doc_3.txt").readText()

//    val testText =
//            File("paragraphs/Biology/doc_0.txt").readText() +
//                    File("paragraphs/People/doc_0.txt").readText()
    return embedder.embed(testText, nSamples = 500, nIterations = 2000, smooth = false)
}


fun testText(embedder: KotlinEmbedding): TopicMixtureResult {
    val testText = """
        William Henry Gates III (born October 28, 1955) is an American business magnate, investor, author, philanthropist, humanitarian, and principal founder of Microsoft Corporation.[2][3] During his career at Microsoft, Gates held the positions of chairman, CEO and chief software architect, while also being the largest individual shareholder until May 2014.

In 1975, Gates and Paul Allen launched Microsoft, which became the world's largest PC software company.[4][a] Gates led the company as chief executive officer until stepping down in January 2000, but he remained as chairman and created the position of chief software architect for himself.[7] In June 2006, Gates announced that he would be transitioning from full-time work at Microsoft to part-time work and full-time work at the Bill & Melinda Gates Foundation, which was established in 2000.[8] He gradually transferred his duties to Ray Ozzie and Craig Mundie.[9] He stepped down as chairman of Microsoft in February 2014 and assumed a new post as technology adviser to support the newly appointed CEO Satya Nadella.[10]

Gates is one of the best-known entrepreneurs of the personal computer revolution. He has been criticized for his business tactics, which have been considered anti-competitive. This opinion has been upheld by numerous court rulings.[11]

Since 1987, Gates has been included in the Forbes list of the world's wealthiest people, an index of the wealthiest documented individuals, excluding and ranking against those with wealth that is not able to be completely ascertained.[12][13] From 1995 to 2017, he held the Forbes title of the richest person in the world all but four of those years, and held it consistently from March 2014 – July 2017, with an estimated net worth of US${'$'}89.9 billion as of October 2017.[1] However, on July 27, 2017, and since October 27, 2017, he has been surpassed by Amazon founder and CEO Jeff Bezos, who had an estimated net worth of US${'$'}90.6 billion at the time.[14]

Later in his career and since leaving Microsoft, Gates pursued a number of philanthropic endeavors. He donated large amounts of money to various charitable organizations and scientific research programs through the Bill & Melinda Gates Foundation.[15] In 2009, Gates and Warren Buffett founded The Giving Pledge, whereby they and other billionaires pledge to give at least half of their wealth to philanthropy.[16] The foundation works to save lives and improve global health, and is working with Rotary International to eliminate polio.[17] As of February 17, 2018, Gates had a net worth of ${'$'}91.7 billion, making him the second-richest person in the world, behind Bezos.
        """

//    Computers : 0.4246420329698797
//    People : 0.129298112951467
//    Society : 0.12527625637420642
//    Events : 0.11531319322281833
//    Organizations : 0.10011617964867575
//    Games : 0.06963897338413672
//    Politics : 0.015609683816930661
//    Science : 0.01287361514520954
//    Environments : 0.0036717321563766387
//    Fashion : 0.003560220330299228

    return embedder.embed(testText, nSamples = 500, nIterations = 1000, smooth = false)
}

fun main(args: Array<String>) {
    val indexLoc = "/home/hcgs/data_science/index"

    val embedder = KotlinEmbedding(indexLoc)
    embedder.loadTopics("paragraphs/",
            filterList = listOf())
//    testBasisParagraphs(embedder).reportResults()
    testText(embedder).reportResults()

//    val myquery = "Arachnophobia signs and symptoms"
//    val queryResults = embedder.query(myquery, 100).mapIndexed { index, s -> index.toString() to s  }.toMap()
//
//    embedder.loadQueries(myquery, nQueries = 100)
//    val expanded = embedder.expandQueryText(myquery, 10)
//
//    val results = embedder.embed(expanded, nSamples = 3000, nIterations = 100, smooth = false)
//    results.results.entries.sortedByDescending { it.value }
//        .forEach { (k,v) -> println("${queryResults[k]}:\n\t$v") }

//    println(expanded)


//    testBasisParagraphs(embedder).reportResults()
//    testText(embedder).reportResults()



}