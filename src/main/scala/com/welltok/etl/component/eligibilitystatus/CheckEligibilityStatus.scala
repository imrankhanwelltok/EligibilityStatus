package com.welltok.etl.component.eligibilitystatus

import org.apache.spark.sql.DataFrame

import com.welltok.etl.component.eligibilitystatus.service.CassandraServiceImpl
import com.welltok.etl.component.eligibilitystatus.service.DBService
import com.welltok.etl.engine.component.StepComponent
import com.welltok.etl.engine.model.StepContext
import com.welltok.etl.engine.service.Logger
import com.welltok.etl.engine.service.LoggerFactory




class CheckEligibilityStatus extends StepComponent {
  val logger: Logger = LoggerFactory.getLogger(classOf[CheckEligibilityStatus])
  var dbConnectionObject: DBService = null
  def process(stepContext: StepContext): Object =
    {
      logger.info("************")

      try {
        // initialize the services for the class

        initServices(stepContext)

        //  Get Args from StepContext.arguments
        val successDf = stepContext.getSuccessDataFrame()

        val sponsorId = if (stepContext.getArguments().containsKey("sponsorID")) stepContext.getArguments().get("sponsorID").toString() else null
        val eventId = if (stepContext.getArguments.containsKey("processEventId")) stepContext.getArguments().get("processEventId").toString() else null
        val keySpaceName: String = "person"
        val loadTableName: String = "eligibility_status"
        val whereClause: String = """sponsor_id = '""" + sponsorId + """'"""

        val eligibilityStatusDf: DataFrame = dbConnectionObject.loadTable(keySpaceName, loadTableName, whereClause)
        eligibilityStatusDf.cache()

        logger.info("***************")

        println("successDf count: " + successDf.count())

        //Populate successdataframe with Status field
        val recordsWithStatusDf: DataFrame = dbConnectionObject.populateStatus(successDf, eligibilityStatusDf)
        logger.info("recordsWithStatusDf count: " + recordsWithStatusDf.count())

        //Get new data with status changed and needs to be written to person.eligibility_status table
        val getEligibilityNewDataDf = dbConnectionObject.getEligibilityDf(recordsWithStatusDf, eligibilityStatusDf, "currentTable", eventId)
        logger.info("getEligibilityNewDataDf count: " + getEligibilityNewDataDf.count())

        //Get data with status changed and needs to be written to person.eligibility_status_history table
        val getEligibilityHistoryDataDf = dbConnectionObject.getEligibilityDf(recordsWithStatusDf, eligibilityStatusDf, "historyTable", eventId)
        logger.info("getEligibilityHistoryDataDf count: " + getEligibilityHistoryDataDf.count())

        //Write data to current eligibility table
        val statusCurrentTableWrite = dbConnectionObject.writeToCassandra(getEligibilityNewDataDf, "person", "eligibility_status")
        if (statusCurrentTableWrite == true) {
          logger.info("Data successfully writtent into person.eligibility_status table")
        } else {
          logger.info("There seems to be issue while writing data into person.eligibility_status table , please check log for more details ")
        }

        //Write data to current eligibility table
        val statusHistoryTableWrite = dbConnectionObject.writeToCassandra(getEligibilityHistoryDataDf, "person", "eligibility_status_history")
        if (statusHistoryTableWrite == true) {
          logger.info("Data successfully writtent into person.eligibility_status_history table")
        } else {
          logger.info("There seems to be issue while writing data into person.eligibility_status_history table , please check log for more details ")
        }

        var finalDf = recordsWithStatusDf
        finalDf.cache()

        logger.printDataframe(finalDf)

      } catch {
        case e @ (_: Exception | _: Error | _: Throwable) =>
          e.printStackTrace();
          throw new RuntimeException(s" Error while fetching the data")
      }
      new Object()
    }

  /**
   * Nothing to do during rollback
   */
  def rollback(stepContext: StepContext): Object = {

    new Object()
  }

  /**
   * Initialize services for this class, using stepContext
   */
  def initServices(stepContext: StepContext) {
    if (dbConnectionObject == null) {
      dbConnectionObject = new CassandraServiceImpl(stepContext)
      logger.info("db connection object intialized...")
    }
  }
}
