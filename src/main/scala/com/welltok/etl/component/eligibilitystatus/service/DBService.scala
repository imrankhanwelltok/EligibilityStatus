package com.welltok.etl.component.eligibilitystatus.service

import org.apache.spark.sql.DataFrame

trait DBService {
  def loadTable(keySpace: String, tableName: String, whereClause: String): DataFrame
  def populateStatus(successDf: DataFrame, eligibilityStatusDf: DataFrame): DataFrame
  def getEligibilityDf(recordsWithStatusDf: DataFrame, eligibilityStatusDf: DataFrame, tableType: String, eventId: String): DataFrame
  def writeToCassandra(dataFrame: DataFrame, keyspaceName: String, tableName: String): Boolean
}
