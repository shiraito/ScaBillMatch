package org.apache.spark.ml.feature

import com.typesafe.config._

import org.apache.spark.sql.Dataset

import org.apache.spark.{SparkConf, SparkContext, SparkFiles}
import org.apache.spark.SparkContext._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

import org.apache.spark.ml.linalg.{DenseVector, SparseVector, Vector, Vectors}
import org.apache.spark.sql.DataFrame

//import org.apache.spark.ml.feature.{HashingTF, IDF, RegexTokenizer, Tokenizer, NGram, StopWordsRemover}

import org.apache.spark.sql.Row
import org.apache.spark.sql.types._

import org.princeton.billmatch.feature._


object ExtractMinHashLSH {

  def scaler = udf((d:Double) => (100.0-100.0*d).toFloat)

  def calculateApproxSimilarityJoin[T <: CustomizedLSHModel[T]](
      lsh: CustomizedLSH[T],
      datasetA: Dataset[_],
      datasetB: Dataset[_],
      threshold: Double,
      state_pair: String,
      output: String): Unit = {

    val model = lsh.fit(datasetA)
    val inputCol = model.getInputCol

    println("Compute actual")
    datasetA.show(400)
    datasetB.show(400)
    val actual = model.approxSimilarityJoin(datasetA, datasetB, threshold)
    actual.show(400)
    actual.printSchema()
    actual.select(col("datasetA.primary_key").alias("pk1"),col("datasetB.primary_key").alias("pk2"),col("distCol")).withColumn("similarity",scaler(col("distCol"))).drop("distCol").write.parquet(output+state_pair)
  }

  def calculateFor2States[T <: CustomizedLSHModel[T]](
      lsh: CustomizedLSH[T],
      pp: List[Long],
      df: Dataset[_],
      threshold: Double,
      output: String): Unit = {

      val part1 = df.filter(col("state") === pp(0))
      val part2 = df.filter(col("state") === pp(1))
      val state_pair = pp(0).toString+"_"+pp(1).toString
      calculateApproxSimilarityJoin(lsh,part1,part2,threshold,state_pair,output)
  }

  def main(args: Array[String]) {

    //Test with actual text data
    val spark = SparkSession.builder().appName("MinHashExample")
      //.config("spark.dynamicAllocation.enabled","true")
      .config("spark.shuffle.service.enabled","true")
      .config("spark.shuffle.memoryFraction","0.6")
      .config("spark.sql.codegen.wholeStage", "true")
      .config("spark.driver.maxResultSize", "10g")
      .getOrCreate()

    import spark.implicits._

    val t0 = System.nanoTime()

    val params = ConfigFactory.load("workflow2")

    lazy val vv: String = params.getString("workflow2.docVersion")
    lazy val inputFile: String = params.getString("workflow2.inputFile")
    val input = spark.read.json(inputFile).filter($"docversion" === vv).filter(Utils.compactSelector_udf(col("content"))).filter(Utils.lengthSelector_udf(col("content")))

    ///val npartitions = (4*input.count()/1000).toInt
    val bills = input.repartition(400,col("primary_key")).cache()
    bills.explain

    lazy val nGramGranularity = params.getInt("workflow2.nGramGranularity")
    lazy val numTextFeatures = params.getInt("workflow2.numTextFeatures")
    lazy val outfile = params.getString("workflow2.outputFileBase")

    def cleaner_udf = udf((s: String) => s.replaceAll("(\\d|,|:|;|\\?|!)", ""))
    val cleaned_df = bills.withColumn("cleaned",cleaner_udf(col("content")))

    //tokenizer = Tokenizer(inputCol="text", outputCol="words")
    val tokenizer = new RegexTokenizer().setInputCol("cleaned").setOutputCol("words").setPattern("\\W")
    val tokenized_df = tokenizer.transform(cleaned_df)

    //remove stopwords 
    val remover = new StopWordsRemover().setInputCol("words").setOutputCol("filtered")
    val prefeaturized_df = remover.transform(tokenized_df).select(col("primary_key"),col("content"),col("docversion"),col("docid"),col("state"),col("year"),col("filtered"))

    val ngram = new NGram().setN(nGramGranularity).setInputCol("filtered").setOutputCol("ngram")
    val ngram_df = ngram.transform(prefeaturized_df)

    //hashing
    val hashingTF = new HashingTF().setInputCol("ngram").setOutputCol("keys").setNumFeatures(numTextFeatures)
    val featurized_df = hashingTF.transform(ngram_df).select("keys","primary_key","state")
    featurized_df.show()

    val mh = new CustomizedMinHashLSH().setNumHashTables(100)
      .setInputCol("keys")
      .setOutputCol("values")
      .setSeed(12345)

    //get distinct states
    val states = featurized_df.select("state").distinct().as[Long].rdd.collect().toList.combinations(2).toList.par
    val state_pairs_results = states.foreach(pair => calculateFor2States(mh,pair,featurized_df,0.99,outfile))

    val t1 = System.nanoTime()
    println("Elapsed time: " + (t1 - t0)/1000000000 + "s")

    spark.stop()
  }

}
