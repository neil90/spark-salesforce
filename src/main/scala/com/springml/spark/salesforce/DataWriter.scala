package com.springml.spark.salesforce

import com.sforce.soap.partner.sobject.SObject
import com.springml.spark.salesforce.Utils._
import org.apache.log4j.Logger
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row

/**
 * Created by madhu on 13/7/15.
 */
class DataWriter (val userName: String, val password: String, val datasetName: String) extends Serializable{
  @transient val logger = Logger.getLogger(classOf[DefaultSource])
  def writeMetadata(metaDataJson: String): Option[String] = {
    val partnerConnection = createConnection(userName, password)
    val sobj = new SObject()
    sobj.setType("InsightsExternalData")
    sobj.setField("Format", "Csv")
    sobj.setField("EdgemartAlias", datasetName)
    sobj.setField("MetadataJson", metaDataJson.getBytes)
    sobj.setField("Operation", "Overwrite")
    sobj.setField("Action", "None")
    val results = partnerConnection.create(Array(sobj))
    results.map(saveResult => {
      if (saveResult.isSuccess) {
        logger.info("successfully wrote metadata")
        Some(saveResult.getId)
      } else {
        logger.error("failed to write metadata")
        logSaveResultError(saveResult)
        None
      }
    }).head
  }


  def writeData(rdd: RDD[Row],metadataId:String): Boolean = {
    val csvRDD = rdd.map(row => row.toSeq.map(value => value.toString).mkString(","))
    csvRDD.mapPartitionsWithIndex {
      case (index, iterator) => {
        @transient val logger = Logger.getLogger(classOf[DataWriter])
        val partNumber = index + 1
        val data = iterator.toArray.mkString("\n")
        val sobj = new SObject()
        sobj.setType("InsightsExternalDataPart")
        sobj.setField("DataFile", data.getBytes)
        sobj.setField("InsightsExternalDataId", metadataId)
        sobj.setField("PartNumber", partNumber)
        val partnerConnection = Utils.createConnection(userName, password)
        val results = partnerConnection.create(Array(sobj))

        val resultSuccess = results.map(saveResult => {
          if (saveResult.isSuccess) {
            logger.info(s"successfully written for $datasetName for part $partNumber")
            true
          } else {
            logger.info(s"error writing for $datasetName for part $partNumber")
            logSaveResultError(saveResult)
            false
          }
        }).head
        List(resultSuccess).iterator
      }

    }.reduce((a, b) => a && b)
  }

  def commit(id: String): Boolean = {

    val partnerConnection = Utils.createConnection(userName, password)
    val sobj = new SObject()

    sobj.setType("InsightsExternalData")

    sobj.setField("Action", "Process")

    sobj.setId(id)

    val results = partnerConnection.update(Array(sobj))

    val saved = results.map(saveResult => {
      if (saveResult.isSuccess) {
        logger.info("committing complete")
        true
      } else {
        println(saveResult.getErrors.toList)
        false
      }
    }).reduce((a, b) => a && b)
    saved
  }


}