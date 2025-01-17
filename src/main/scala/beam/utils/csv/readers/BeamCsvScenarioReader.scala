package beam.utils.csv.readers

import java.util.{Map => JavaMap}

import beam.utils.scenario._
import beam.utils.scenario.matsim.BeamScenarioReader
import beam.utils.{FileUtils, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.reflect.ClassTag

object BeamCsvScenarioReader extends BeamScenarioReader with LazyLogging {
  override def inputType: InputType = InputType.CSV

  override def readPersonsFile(path: String): Array[PersonInfo] = {
    readAs[PersonInfo](path, "readPersonsFile", toPersonInfo)
  }
  override def readPlansFile(path: String): Array[PlanElement] = {
    readAs[PlanElement](path, "readPlansFile", toPlanInfo)

  }
  override def readHouseholdsFile(householdsPath: String, vehicles: Iterable[VehicleInfo]): Array[HouseholdInfo] = {
    val householdToNumberOfCars = vehicles.groupBy(_.householdId).map {
      case (householdId, listOfCars) => (householdId, listOfCars.size)
    }
    readAs[HouseholdInfo](householdsPath, "readHouseholdsFile", toHouseholdInfo(householdToNumberOfCars))
  }

  private[readers] def readAs[T](path: String, what: String, mapper: JavaMap[String, String] => T)(
    implicit ct: ClassTag[T]
  ): Array[T] = {
    ProfilingUtils.timed(what, x => logger.info(x)) {
      FileUtils.using(new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)) { csvRdr =>
        val header = csvRdr.getHeader(true)
        Iterator.continually(csvRdr.read(header: _*)).takeWhile(_ != null).map(mapper).toArray
      }
    }
  }

  private[readers] def toHouseholdInfo(
    householdIdToVehiclesSize: Map[String, Int]
  )(rec: JavaMap[String, String]): HouseholdInfo = {
    val householdId = getIfNotNull(rec, "householdId")
    val cars = householdIdToVehiclesSize.get(householdId) match {
      case Some(total) => total
      case None =>
        logger.warn(s"HouseholdId [$householdId] has no cars associated")
        0
    }
    HouseholdInfo(
      householdId = HouseholdId(householdId),
      cars = cars,
      income = getIfNotNull(rec, "incomeValue").toDouble,
      locationX = getIfNotNull(rec, "locationX").toDouble,
      locationY = getIfNotNull(rec, "locationY").toDouble
    )
  }

  private[readers] def toPlanInfo(rec: java.util.Map[String, String]): PlanElement = {
    val personId = getIfNotNull(rec, "personId")
    val planElementType = getIfNotNull(rec, "planElementType")
    val planElementIndex = getIfNotNull(rec, "planElementIndex").toInt
    val activityType = Option(rec.get("activityType"))
    PlanElement(
      personId = PersonId(personId),
      planElementType = planElementType,
      planElementIndex = planElementIndex,
      activityType = activityType,
      activityLocationX = Option(rec.get("activityLocationX")).map(_.toDouble),
      activityLocationY = Option(rec.get("activityLocationY")).map(_.toDouble),
      activityEndTime = Option(rec.get("activityEndTime")).map(_.toDouble),
      legMode = Option(rec.get("legMode")).map(_.toString)
    )
  }

  private def toPersonInfo(rec: JavaMap[String, String]): PersonInfo = {
    val personId = getIfNotNull(rec, "personId")
    val householdId = getIfNotNull(rec, "householdId")
    val age = getIfNotNull(rec, "age").toInt
    val rank = getIfNotNull(rec, "householdRank", "0").toInt
    PersonInfo(personId = PersonId(personId), householdId = HouseholdId(householdId), rank = rank, age = age)
  }

  private def toVehicle(rec: JavaMap[String, String]): VehicleInfo = {
    VehicleInfo(
      vehicleId = getIfNotNull(rec, "vehicleId"),
      vehicleTypeId = getIfNotNull(rec, "vehicleTypeId"),
      householdId = getIfNotNull(rec, "householdId")
    )
  }

  private def getIfNotNull(rec: JavaMap[String, String], column: String, default: String = null): String = {
    val v = rec.getOrDefault(column, default)
    assert(v != null, s"Value in column '$column' is null")
    v
  }

  override def readVehiclesFile(vehiclesFilePath: String): Iterable[VehicleInfo] = {
    readAs[VehicleInfo](vehiclesFilePath, "vehiclesFile", toVehicle)
  }
}
