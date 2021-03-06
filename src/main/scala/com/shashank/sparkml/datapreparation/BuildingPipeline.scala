package com.shashank.sparkml.datapreparation

import java.util.Date

import com.shashank.sparkml.util.DataUtil
import org.apache.spark.ml.{Pipeline, PipelineStage}
import org.apache.spark.ml.classification.DecisionTreeClassifier
import org.apache.spark.ml.feature.{MinMaxScaler, OneHotEncoder, StringIndexer, VectorAssembler}
import org.apache.spark.ml.regression.DecisionTreeRegressor
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.{NumericType, StringType, StructType}

import scala.collection.mutable

/**
  * Created by shashank on 04/11/2017.
  */
object BuildingPipeline {

  def createPipeline(schema:StructType, labelColumn:String):Array[PipelineStage] = {
    val featureColumns = mutable.ArrayBuffer[String]()
    val preprocessingStages = schema.fields.filter(_.name != labelColumn).flatMap(field => {
      field.dataType match {
        case stringType:StringType =>
          val naValuesHandler = new NaValuesHandler()
          naValuesHandler.setInputCol(field.name)

          val nullHandler = new NullHandlerEstimator()
          nullHandler.setInputCol(field.name).setHandleWith("NA")

          val stringIndexer = new StringIndexer()
          stringIndexer.setInputCol(field.name).setOutputCol(s"${field.name}_indexed")

          val oneHotEncoder = new OneHotEncoder()
          oneHotEncoder.setInputCol(s"${field.name}_indexed").setOutputCol(s"${field.name}_encoded")

          featureColumns += (s"${field.name}_encoded")
          Array[PipelineStage](naValuesHandler, nullHandler, stringIndexer, oneHotEncoder)

        case numericType:NumericType =>
          val nullHandler = new NullHandlerEstimator()
          nullHandler.setInputCol(field.name).setHandleWith("mean")

          featureColumns += (field.name)

          Array[PipelineStage](nullHandler)

        case _ =>
          Array.empty[PipelineStage]
      }
    })


    val vectorAssembler = new VectorAssembler()
    vectorAssembler.setInputCols(featureColumns.toArray).setOutputCol("features")

    val algorithmStages = schema.apply(labelColumn).dataType match {
      case stringType:StringType =>
        val naValuesHandler = new NaValuesHandler()
        naValuesHandler.setInputCol(labelColumn)

        val nullHandler = new NullHandlerEstimator()
        nullHandler.setInputCol(labelColumn).setHandleWith("NA")

        val stringIndexer = new StringIndexer()
        stringIndexer.setInputCol(labelColumn).setOutputCol(s"${labelColumn}_indexed")

        val decisionTreeClassifier = new DecisionTreeClassifier()
        decisionTreeClassifier.setFeaturesCol("features").setLabelCol(s"${labelColumn}_indexed")

        Array(naValuesHandler, nullHandler, stringIndexer, decisionTreeClassifier)

      case numericType:NumericType =>
        val nullHandler = new NullHandlerEstimator()
        nullHandler.setInputCol(labelColumn).setHandleWith("mean")

        val castTransformer = new CastTransformer()
        castTransformer.setInputCol(labelColumn)

        val decisionTreeRegressor = new DecisionTreeRegressor()
        decisionTreeRegressor.setFeaturesCol("features").setLabelCol(labelColumn)

        Array(nullHandler, castTransformer, decisionTreeRegressor)
    }

    (preprocessingStages :+ vectorAssembler) ++ algorithmStages
  }

  def main(args: Array[String]) {
    val sparkSession = SparkSession.builder.master("local").appName("example").getOrCreate()
    val data = DataUtil.loadCsv(sparkSession, "src/main/resources/airlines.csv")

    data.printSchema()

    val startTime = new Date()
    val pipelineStages = createPipeline(data.schema, "Distance")
    val pipeline = new Pipeline()
    pipeline.setStages(pipelineStages)
    val pipelineModel = pipeline.fit(data)
    val endTime = new Date()

    val transformedData = pipelineModel.transform(data)
    //transformedData.explain(true)

    println(s"Number of stages for a ${data.columns.length} column dataset is " + pipelineModel.stages.length + s" training time taken is ${(endTime.getTime - startTime.getTime)/1000}s")

  }

}
