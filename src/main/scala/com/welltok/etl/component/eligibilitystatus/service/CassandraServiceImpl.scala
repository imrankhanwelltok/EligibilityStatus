package com.welltok.etl.component.eligibilitystatus.service

import java.text.SimpleDateFormat
import java.util.Calendar

import scala.collection.mutable.Map

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.SaveMode

import com.welltok.etl.engine.model.StepContext
import com.welltok.etl.engine.service.Logger
import com.welltok.etl.engine.service.LoggerFactory

class CassandraServiceImpl(stepContext: StepContext) extends DBService {

  val logger: Logger = LoggerFactory.getLogger(classOf[CassandraServiceImpl])

  val sqlContext = SQLContext.getOrCreate(stepContext.getJavaSparkContext.sc)

  def loadTable(keySpaceName: String, tableName: String, whereClause: String): DataFrame =
    {

      val keySpace = keySpaceName

      var eligibilityStatusDf: DataFrame = null
      try {
        eligibilityStatusDf = sqlContext
          .read
          .format("org.apache.spark.sql.cassandra")
          .options(Map("keyspace" -> keySpace, "table" -> tableName))
          .load()
          .cache()

        if (whereClause != null) {
          eligibilityStatusDf = eligibilityStatusDf.where(whereClause)
        }

        eligibilityStatusDf

      } catch {
        case e @ (_: Exception | _: Error | _: Throwable) =>
          {
            e.printStackTrace()
            throw new RuntimeException(s"Error while loading table ${tableName}" + e.getMessage(), e)
          }
      }
    }

  def populateStatus(successDf: DataFrame, eligibilityStatusDf: DataFrame): DataFrame =
    {
      var recordWithStatusDf: DataFrame = null
      try {
        recordWithStatusDf = sqlContext.sql("""
                                    Select  succ.*,
                                            case 
                                                when (succ.is_terminated = 'true' OR succ.is_terminated == 'TRUE') then 'terminated'
                                                when (succ.is_terminated != 'true' OR succ.is_terminated != 'TRUE') AND (curr.platform_id is null OR curr.sponsor_id is null) then 'unclaimed'
                                                when (succ.is_terminated != 'true' OR succ.is_terminated != 'TRUE') AND (curr.platform_id  is not null AND curr.sponsor_id is not null ) and curr.status = 'claimed' then 'claimed'
                                                when (succ.is_terminated != 'true' OR succ.is_terminated != 'TRUE') AND (curr.platform_id  is not null AND curr.sponsor_id is not null) and curr.status = 'terminated' then 'terminated'
                                                when (succ.is_terminated != 'true' OR succ.is_terminated != 'TRUE') AND (curr.platform_id  is not null AND curr.sponsor_id is not null) and curr.status = 'unclaimed' then 'unclaimed'
                                            end as status
                                    from successDf succ left join eligibilityStatusDf curr
                                    on succ.platform_id = curr.platform_id and
                                    succ.sponsor_id = curr.sponsor_id
                                    order by succ.platform_id 
                                """)

        recordWithStatusDf
      } catch {
        case e @ (_: Exception | _: Error | _: Throwable) =>
          {
            e.printStackTrace()
            throw new RuntimeException(s"Error while creating new finalDF" + e.getMessage(), e)
          }
      }
    }
  def getEligibilityDf(recordsWithStatusDf: DataFrame, eligibilityStatusDf: DataFrame, tableType: String, eventId: String): DataFrame =
    {
      try {
        var eligibilityStatusDataDf: DataFrame = null
        val changeStatusDataDf: DataFrame = recordsWithStatusDf.select("platform_id", "sponsor_id", "status").except(eligibilityStatusDf.select("platform_id", "sponsor_id", "status")).select("platform_id", "sponsor_id", "status").toDF()
        changeStatusDataDf.show()
        eligibilityStatusDf.show()

        //val eligibilityStatusDf1 :DataFrame = eligibilityStatusDf

        if (tableType == "currentTable") {
          //val eligibilityStatusDataNewTempDf : DataFrame = recordsWithStatusDf.join(changeStatusDataDf , recordsWithStatusDf("platform_id") === changeStatusDataDf("platform_id") &&  recordsWithStatusDf("sponsor_id") === changeStatusDataDf("sponsor_id")).select(recordsWithStatusDf("platform_id"), recordsWithStatusDf("sponsor_id"), recordsWithStatusDf("member_id"), recordsWithStatusDf("sub_client"), recordsWithStatusDf("status"))
          //val eligibilityStatusDataNewTempDf = recordsWithStatusDf.join(changeStatusDataDf).where(recordsWithStatusDf("platform_id") === changeStatusDataDf("platform_id")).where(recordsWithStatusDf("sponsor_id") === changeStatusDataDf("sponsor_id")).select(recordsWithStatusDf("platform_id"), recordsWithStatusDf("sponsor_id"), recordsWithStatusDf("member_id"), recordsWithStatusDf("sub_client"), recordsWithStatusDf("status"))

          val today: java.util.Date = Calendar.getInstance.getTime
          val timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
          val now: String = timeFormat.format(today)
          val transaction_date = java.sql.Timestamp.valueOf(now)
          //val addRemainingColumns = List(("created_by", lit("process_engine").as("StringType")), ("cw_user_id", lit("").as("StringType")), ("cw_user_identity_id", lit("").as("StringType")), ("cw_user_uuid", lit("").as("StringType")), ("event_id", lit(eventId).as("StringType")), ("solr_query", lit("").as("StringType")), ("transcation_date", lit(transaction_date).as("TimeStampType")))
          //eligibilityStatusDataDf = addRemainingColumns.foldLeft(eligibilityStatusDataNewTempDf) { (tempDf, colName) => tempDf.withColumn(colName._1, colName._2) }
          eligibilityStatusDataDf = sqlContext.sql("""select  recordsWithStatusDf.platform_id, 
                                                                  recordsWithStatusDf.sponsor_id, 
                                                                  "process_engine" as created_by, 
                                                                  "" as cw_user_id, 
                                                                  "" as cw_user_identity_id, 
                                                                  "" as cw_user_uuid, 
                                                                  $eventId as event_id, 
                                                                  recordsWithStatusDf.member_id, 
                                                                  "" as solr_query, 
                                                                  changeStatusDataDf.succ_status, 
                                                                  recordsWithStatusDf.sub_client, 
                                                                  $transaction_date as transaction_date
                                                          From   recordsWithStatusDf recordsWithStatusDf inner join changeStatusDataDf changeStatusDataDf
                                                                   on 	  recordsWithStatusDf.platform_id = changeStatusDataDf.platform_id and
                                                                      	  recordsWithStatusDf.sponsor_id = changeStatusDataDf.sponsor_id
                                                                """)

        } else if (tableType == "historyTable") {
          eligibilityStatusDataDf = sqlContext.sql("""select 	recordsWithStatusDf.platform_id, 
                                                                    recordsWithStatusDf.sponsor_id, 
                                                                    recordsWithStatusDf.created_by, 
                                                                    recordsWithStatusDf.cw_user_id, 
                                                                    recordsWithStatusDf.cw_user_identity_id, 
                                                                    recordsWithStatusDf.cw_user_uuid, 
                                                                    recordsWithStatusDf.event_id, 
                                                                    recordsWithStatusDf.member_id, 
                                                                    recordsWithStatusDf.solr_query, 
                                                                    changeStatusDataDf.succ_status, 
                                                                    recordsWithStatusDf.sub_client, 
                                                                    recordsWithStatusDf.transaction_date
                                                             From   recordsWithStatusDf recordsWithStatusDf inner join changeStatusDataDf changeStatusDataDf
                                                             on 	  recordsWithStatusDf.platform_id = changeStatusDataDf.platform_id and
                                                                    recordsWithStatusDf.sponsor_id = changeStatusDataDf.sponsor_id
                                                                """)
        } else {
          logger.info("Incorrect tableType was passed as parameter, Please pass either currentTable or historyTable as value to get the eligibilityStatusDf populated")
        }
        eligibilityStatusDataDf
      } catch {
        case e @ (_: Exception | _: Error | _: Throwable) =>
          {
            e.printStackTrace()
            throw new RuntimeException(s"Error while creating EligibilityStatusDf" + e.getMessage(), e)
          }
      }
    }

  def writeToCassandra(dataFrame: DataFrame, keyspaceName: String, tableName: String): Boolean =
    {
      try {
        val sqlContext = SQLContext.getOrCreate(stepContext.getJavaSparkContext.sc)

        if (dataFrame.count() > 0) {
          dataFrame
            .write
            .format("org.apache.spark.sql.cassandra")
            .options(Map("keyspace" -> keyspaceName, "table" -> tableName))
            .mode(SaveMode.Append)
            .save()

          logger.info("Number of new records with status changed inserted into cassandra table : " + keyspaceName + "." + tableName + " is :" + dataFrame.count())
        } else {
          logger.info("It seems there were 0 records in DataFrame passed as an argument to this function.")
        }

        return true
      } catch {
        case e @ (_: Exception | _: Error | _: Throwable) =>
          {
            e.printStackTrace()
            throw new RuntimeException(s"Error while writing data to Cassandra" + e.getMessage(), e)
          }
      }
    }

}