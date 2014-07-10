package au.org.ala.biocache.index

import au.org.ala.biocache._
import org.apache.commons.io.FileUtils
import java.io.File
import org.apache.lucene.misc.IndexMergeTool
import scala.io.Source
import java.net.URL
import scala.util.parsing.json.JSON
import java.lang.Thread
import scala.collection.mutable.ArrayBuffer
import java.io.FileWriter
import au.com.bytecode.opencsv.CSVWriter
import scala.collection.mutable.HashSet
import org.apache.commons.lang3.StringUtils
import au.org.ala.biocache.processor.{RecordProcessor, Processors, LocationProcessor}
import au.org.ala.biocache.load.FullRecordMapper
import au.org.ala.biocache.vocab.{ErrorCode, AssertionCodes}
import au.org.ala.biocache.util.{Json, OptionParser}
import au.org.ala.biocache.model.QualityAssertion
import org.slf4j.LoggerFactory
import au.org.ala.biocache.caches.LocationDAO

trait Counter {
  var counter = 0

  def addToCounter(amount: Int) = counter += amount

  var startTime = System.currentTimeMillis
  var finishTime = System.currentTimeMillis

  def printOutStatus(threadId: Int, lastKey: String, runnerType: String) = {
    finishTime = System.currentTimeMillis
    println("[" + runnerType + " Thread " + threadId + "] " + counter + " >> Last key : " + lastKey + ", records per sec: " + 1000f / (((finishTime - startTime).toFloat) / 1000f))
    startTime = System.currentTimeMillis
  }
}

/**
 * A trait that will calculate the ranges to use for a multiple threaded process.
 */
trait RangeCalculator {

  val logger = LoggerFactory.getLogger("RangeCalculator")

  /**
   * For a give webservice URL, calculate a partitioning per thread
   */
  def calculateRanges(threads: Int, query: String = "*:*", start: String = "", end: String = ""): Array[(String, String)] = {

    val firstRequest = Config.biocacheServiceUrl + "/occurrences/search?q=" + query + "&pageSize=1&facet=off&sort=row_key&dir=asc"
    val json = JSON.parseFull(Source.fromURL(new URL(firstRequest)).mkString)
    if (!json.isEmpty) {
      val totalRecords = json.get.asInstanceOf[Map[String, Object]].getOrElse("totalRecords", 0).asInstanceOf[Double].toInt
      logger.info("Total records: " + totalRecords)

      val pageSize = totalRecords.toInt / threads

      var lastKey = start
      val buff = Array.fill(threads)(("", ""))

      for (i <- 0 until threads) {
        val json = JSON.parseFull(Source.fromURL(
          new URL(Config.biocacheServiceUrl + "/occurrences/search?q=" + query + "&facets=row_key&pageSize=0&flimit=1&fsort=index&foffset=" + (i * pageSize))).mkString)
        val facetResults = json.get.asInstanceOf[Map[String, Object]]
          .getOrElse("facetResults", List[Map[String, Object]]())
          .asInstanceOf[List[Map[String, Object]]]

        val rowKey = facetResults.head.get("fieldResult").get.asInstanceOf[List[Map[String, String]]].head.getOrElse("label", "")
        logger.info("Retrieved row key: " + rowKey)

        if (i > 0) {
          buff(i - 1) = (lastKey, rowKey)
        }
        //we want the first key to be ""
        if (i != 0)
          lastKey = rowKey
      }

      buff(buff.length - 1) = (lastKey, end)

      buff
    } else {
      Array()
    }
  }

  def generateRanges(keys: Array[String], start: String, end: String): Array[(String, String)] = {
    val buff = new ArrayBuffer[(String, String)]
    var i = 0
    while (i < keys.size) {
      if (i == 0)
        buff += ((start, keys(i)))
      else if (i == keys.size - 1)
        buff += ((keys(i - 1), end))
      else
        buff += ((keys(i - 1), keys(i)))
      i += 1
    }
    buff.toArray[(String, String)]
  }
}

class ColumnExporter(centralCounter: Counter, threadId: Int, startKey: String, endKey: String, columns: List[String]) extends Runnable {

  val logger = LoggerFactory.getLogger("ColumnExporter")

  def run {

    val outWriter = new FileWriter(new File( Config.tmpWorkDir + "/fullexport" + threadId + ".txt"))
    val writer = new CSVWriter(outWriter, '\t', '"', '\\')
    writer.writeNext(Array("rowKey") ++ columns.toArray[String])
    val start = System.currentTimeMillis
    var startTime = System.currentTimeMillis
    var finishTime = System.currentTimeMillis
    var counter = 0
    val pageSize = 10000
    Config.persistenceManager.pageOverSelect("occ", (key, map) => {
      counter += 1
      exportRecord(writer, columns, key, map)
      if (counter % pageSize == 0 && counter > 0) {
        centralCounter.addToCounter(pageSize)
        finishTime = System.currentTimeMillis
        centralCounter.printOutStatus(threadId, key, "Column Reporter")
        startTime = System.currentTimeMillis
      }
      true
    }, startKey, endKey, 1000, columns: _*)

    val fin = System.currentTimeMillis
    logger.info("[Exporter Thread " + threadId + "] " + counter + " took " + ((fin - start).toFloat) / 1000f + " seconds")
  }

  def exportRecord(writer: CSVWriter, fieldsToExport: List[String], guid: String, map: Map[String, String]) {
    val line = Array(guid) ++ (for (field <- fieldsToExport) yield map.getOrElse(field, ""))
    writer.writeNext(line)
  }
}

class ColumnReporterRunner(centralCounter: Counter, threadId: Int, startKey: String, endKey: String) extends Runnable {

  val logger = LoggerFactory.getLogger("ColumnReporterRunner")
  val myset = new HashSet[String]

  def run {
    println("[THREAD " + threadId + "] " + startKey + " TO " + endKey)
    val start = System.currentTimeMillis
    var startTime = System.currentTimeMillis
    var finishTime = System.currentTimeMillis
    var counter = 0
    val pageSize = 10000
    Config.persistenceManager.pageOverAll("occ", (guid, map) => {
      myset ++= map.keySet
      counter += 1
      if (counter % pageSize == 0 && counter > 0) {
        centralCounter.addToCounter(pageSize)
        finishTime = System.currentTimeMillis
        centralCounter.printOutStatus(threadId, guid, "Column Reporter")
        startTime = System.currentTimeMillis
      }
      true
    }, startKey, endKey, 1000)
    val fin = System.currentTimeMillis
    logger.info("[Thread " + threadId + "] " + counter + " took " + ((fin - start).toFloat) / 1000f + " seconds")
    logger.info("[THREAD " + threadId + "] " + myset)
  }
}

class RepairRecordsRunner(centralCounter: Counter, threadId: Int, startKey: String, endKey: String) extends Runnable {
  val logger = LoggerFactory.getLogger("RepairRecordsRunner")
  var counter = 0

  def run {
    val pageSize = 1000
    var counter = 0
    val start = System.currentTimeMillis
    var startTime = System.currentTimeMillis
    var finishTime = System.currentTimeMillis
    logger.info("Starting to repair from " + startKey + " to " + endKey)
    Config.persistenceManager.pageOverSelect("occ", (guid, map) => {
      counter += 1

      val dstatus = map.getOrElse("duplicationStatus.p", "")
      if (dstatus.equals("D")) {
        val qa = Config.occurrenceDAO.getSystemAssertions(guid).find(_.getCode == AssertionCodes.INFERRED_DUPLICATE_RECORD.code)
        if (qa.isEmpty) {
          //need to add the QA
          Config.occurrenceDAO.addSystemAssertion(guid, QualityAssertion(AssertionCodes.INFERRED_DUPLICATE_RECORD, "Record has been inferred as closely related to  " + map.getOrElse("associatedOccurrences.p", "")), false, false)
          logger.info("REINDEX:::" + guid)
        }
      }
      if (counter % pageSize == 0 && counter > 0) {
        centralCounter.addToCounter(pageSize)
        finishTime = System.currentTimeMillis
        centralCounter.printOutStatus(threadId, guid, "Repairer")
        startTime = System.currentTimeMillis
      }
      true
    }, startKey, endKey, pageSize, "qualityAssertion", "rowKey", "uuid", "duplicationStatus.p", "associatedOccurrences.p")
  }

  val qaphases = Array("loc.qa", "offline.qa", "class.qa", "bor.qa", "type.qa", "attr.qa", "image.qa", "event.qa")

  def sortOutQas(guid: String, list: List[QualityAssertion]): (String, String) = {
    val failed: Map[String, List[Int]] = list.filter(_.qaStatus == 0).map(_.code).groupBy(qa => Processors.getProcessorForError(qa) + ".qa")
    val gk = AssertionCodes.isGeospatiallyKosher(failed.getOrElse("loc.qa", List()).toArray).toString
    val tk = AssertionCodes.isTaxonomicallyKosher(failed.getOrElse("class.qa", List()).toArray).toString

    val empty = qaphases.filterNot(p => failed.contains(p)).map(_ -> "[]")
    val map = Map("geospatiallyKosher" -> gk, "taxonomicallyKosher" -> tk) ++ failed.filterNot(_._1 == ".qa").map {
      case (key, value) => {
        (key, Json.toJSON(value.toArray))
      }
    } ++ empty
    //revise the properties in the db
    Config.persistenceManager.put(guid, "occ", map)

    //check to see if there is a tool QA and remove one
    val dupQA = list.filter(_.code == AssertionCodes.INFERRED_DUPLICATE_RECORD.code)
    //dupQA.foreach(qa => println(qa.getComment))
    if (dupQA.size > 1) {
      val newList: List[QualityAssertion] = list.diff(dupQA) ++ List(dupQA(0))
      //println("Original size " + list.length + "  new size =" + newList.length)
      Config.persistenceManager.putList(guid, "occ", FullRecordMapper.qualityAssertionColumn, newList, classOf[QualityAssertion], true)
    }

    //println("FAILED: " + failed)
    //println("The map to add " + map)
    (gk, tk)
  }
}

class DatumRecordsRunner(centralCounter: Counter, threadId: Int, startKey: String, endKey: String) extends Runnable {
  val logger = LoggerFactory.getLogger("DatumRecordsRunner")
  val processor = new RecordProcessor
  var ids = 0
  val threads = 2
  var batches = 0

  def run {
    val pageSize = 1000
    var counter = 0
    var numIssue = 0
    val start = System.currentTimeMillis
    var startTime = System.currentTimeMillis
    var finishTime = System.currentTimeMillis
    //var buff = new ArrayBuffer[(FullRecord,FullRecord)]
    logger.info("Starting thread " + threadId + " from " + startKey + " to " + endKey)
    def locProcess = new LocationProcessor
    Config.persistenceManager.pageOverSelect("occ", (guid, map) => {
      counter += 1

      if (StringUtils.isNotBlank(map.getOrElse("geodeticDatum", ""))) {
        //check the precision of the lat/lon
        def lat = map.getOrElse("decimalLatitude", "0")
        def lon = map.getOrElse("decimalLongitude", "0")
        def locqa = Json.toIntArray(map.getOrElse("loc.qa", "[]"))
        if (locProcess.getNumberOfDecimalPlacesInDouble(lat) != locProcess.getNumberOfDecimalPlacesInDouble(lon) && locqa.contains(45)) {
          numIssue += 1
          logger.info("FIXME from THREAD " + threadId + "\t" + guid)
        }
      }

      if (counter % pageSize == 0 && counter > 0) {
        centralCounter.addToCounter(pageSize)
        finishTime = System.currentTimeMillis
        centralCounter.printOutStatus(threadId, guid, "Datum")
        startTime = System.currentTimeMillis
      }
      true;
    }, startKey, endKey, 1000, "decimalLatitude", "decimalLongitude", "rowKey", "uuid", "geodeticDatum", "loc.qa")
    val fin = System.currentTimeMillis
    logger.info("[Datum Thread " + threadId + "] " + counter + " took " + ((fin - start).toFloat) / 1000f + " seconds")
    logger.info("Finished.")
  }
}

class LoadSamplingRunner(centralCounter: Counter, threadId: Int, startKey: String, endKey: String) extends Runnable {
  val logger = LoggerFactory.getLogger("LoadSamplingRunner")
  var ids = 0
  val threads = 2
  var batches = 0

  def run {
    val pageSize = 1000
    var counter = 0
    val start = System.currentTimeMillis
    logger.info("Starting thread " + threadId + " from " + startKey + " to " + endKey)
    Config.persistenceManager.pageOverSelect("occ", (guid, map) => {
      val lat = map.getOrElse("decimalLatitude.p","")
      val lon = map.getOrElse("decimalLongitude.p","")
      if(lat != null && lon != null){
        val point = LocationDAO.getByLatLon(lat, lon)
        if(!point.isEmpty){
          val (location, environmentalLayers, contextualLayers) = point.get
          Config.persistenceManager.put(guid, "occ", Map(
            "el.p"-> Json.toJSON(environmentalLayers),
            "cl.p" -> Json.toJSON(contextualLayers))
          )
        }
        counter += 1
        if(counter % 10000 == 0){
          logger.info("[LoadSamplingRunner Thread " + threadId + "] Import of sample data " + counter + " Last key " + guid)
        }
      }
      true
    }, startKey, endKey, 1000, "decimalLatitude.p", "decimalLongitude.p" )
    val fin = System.currentTimeMillis
    logger.info("[LoadSamplingRunner Thread " + threadId + "] " + counter + " took " + ((fin - start).toFloat) / 1000f + " seconds")
    logger.info("Finished.")
  }
}


class ProcessRecordsRunner(centralCounter: Counter, threadId: Int, startKey: String, endKey: String) extends Runnable {
  val logger = LoggerFactory.getLogger("ProcessRecordsRunner")
  val processor = new RecordProcessor
  var ids = 0
  val threads = 2
  var batches = 0

  def run {
    val pageSize = 1000
    var counter = 0
    val start = System.currentTimeMillis
    var startTime = System.currentTimeMillis
    var finishTime = System.currentTimeMillis
    //var buff = new ArrayBuffer[(FullRecord,FullRecord)]
    println("Starting thread " + threadId + " from " + startKey + " to " + endKey)
    Config.occurrenceDAO.pageOverRawProcessed(rawAndProcessed => {
      counter += 1
      if (!rawAndProcessed.get._1.deleted)
        processor.processRecord(rawAndProcessed.get._1, rawAndProcessed.get._2)
      if (counter % pageSize == 0 && counter > 0) {
        centralCounter.addToCounter(pageSize)
        finishTime = System.currentTimeMillis
        centralCounter.printOutStatus(threadId, rawAndProcessed.get._1.rowKey, "Processor")
        startTime = System.currentTimeMillis
      }
      true
    }, startKey, endKey, 1000)
    val fin = System.currentTimeMillis
    logger.info("[Processor Thread " + threadId + "] " + counter + " took " + ((fin - start).toFloat) / 1000f + " seconds")
    logger.info("Finished.")
  }
}

class IndexRunner(centralCounter: Counter, threadId: Int, startKey: String, endKey: String, sourceConfDirPath: String, targetConfDir: String, pageSize: Int = 200) extends Runnable {

  val logger = LoggerFactory.getLogger("IndexRunner")

  def run {

    val newIndexDir = new File(targetConfDir)
    if (newIndexDir.exists) FileUtils.deleteDirectory(newIndexDir)
    FileUtils.forceMkdir(newIndexDir)

    //create a copy of SOLR home
    val sourceConfDir = new File(sourceConfDirPath)

    FileUtils.copyDirectory(sourceConfDir, newIndexDir)

    FileUtils.copyFileToDirectory(new File(sourceConfDir.getParent + "/solr.xml"), newIndexDir.getParentFile)
    //FileUtils.copyFileToDirectory(new File(sourceConfDir.getParentFile.getParent+"/solr.xml"), newIndexDir.getParentFile.getParentFile)

    //val pageSize = 1000
    logger.info("Set SOLR Home: " + newIndexDir.getParent)
    val indexer = new SolrIndexDAO(newIndexDir.getParent, Config.excludeSensitiveValuesFor, Config.extraMiscFields)
    indexer.solrConfigPath = newIndexDir.getAbsolutePath + "/solrconfig.xml"

    var counter = 0
    val start = System.currentTimeMillis
    var startTime = System.currentTimeMillis
    var finishTime = System.currentTimeMillis
    var check = true
    //page through and create and index for this range
    Config.persistenceManager.pageOverAll("occ", (guid, map) => {
      counter += 1

      val commit = counter % 10000 == 0
      //ignore the record if it has the guid that is the startKey this is because it will be indexed last by the previous thread.
      try {
        if (check) {
          check = false
          if (!guid.equals(startKey)) {
            indexer.indexFromMap(guid, map, commit = commit)
          }
        } else {
          indexer.indexFromMap(guid, map, commit = commit)
        }
      } catch {
        case e:Exception => logger.error("Problem indexing record: " + guid +""  + e.getMessage())
      }

      if (counter % pageSize == 0 && counter > 0) {
        centralCounter.addToCounter(pageSize)
        finishTime = System.currentTimeMillis
        centralCounter.printOutStatus(threadId, guid, "Indexer")
        startTime = System.currentTimeMillis
      }

      true
    }, startKey, endKey, pageSize = pageSize)

    indexer.finaliseIndex(true, true)

    finishTime = System.currentTimeMillis
    logger.info("Total indexing time for this thread" + ((finishTime - start).toFloat) / 1000f + " seconds")
  }
}