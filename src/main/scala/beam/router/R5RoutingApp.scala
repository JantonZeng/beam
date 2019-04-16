package beam.router

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Identify, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.util.Timeout
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{IntermodalUse, Location, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.CAR
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.r5.{DefaultNetworkCoordinator, R5RoutingWorker}
import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.utils.{GpxPoint, GpxWriter}
import com.conveyal.r5.streets.StreetLayer
import com.typesafe.scalalogging.LazyLogging
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Decoder.Result
import io.circe.generic.decoding.DerivedDecoder
import io.circe.generic.encoding.DerivedObjectEncoder
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, HCursor, Json}
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{BasicLocation, Coord, Id}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.vehicles.Vehicle
import shapeless.Lazy

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.reflect.ClassTag

class IdFormat[T](implicit val ct: ClassTag[T]) extends Encoder[Id[T]] with Decoder[Id[T]] {
  override def apply(o: Id[T]): Json = Json.fromString(o.toString)

  override def apply(c: HCursor): Result[Id[T]] = {
    c.as[String].map { id =>
      val clazz = ct.runtimeClass.asInstanceOf[Class[T]]
      Id.create(id, clazz)
    }
  }
}

object LocationFormat extends Encoder[Location] with Decoder[Location] {
  override def apply(a: Location): Json = Json.obj(
    ("x", Json.fromString(a.getX.toString)),
    ("y", Json.fromString(a.getY.toString))
  )

  override def apply(c: HCursor): Result[Location] = {
    for {
      x <- c.downField("x").as[String]
      y <- c.downField("y").as[String]
    } yield {
      new Location(x.toDouble, y.toDouble)
    }
  }
}

class Format[T](implicit decode: Lazy[DerivedDecoder[T]], encode: Lazy[DerivedObjectEncoder[T]])
    extends Encoder[T]
    with Decoder[T] {
  implicit val decoder: Decoder[T] = deriveDecoder[T]
  implicit val encoder: Encoder[T] = deriveEncoder[T]

  override def apply(a: T): Json = encoder.apply(a)

  override def apply(c: HCursor): Result[T] = decoder.apply(c)
}

object AllNeededFormats {
  implicit val locationFormat = LocationFormat
  implicit val beamModeFormat = new Format[BeamMode]
  implicit val vehicleIdFormat = new IdFormat[Vehicle]
  implicit val beamVehicleTypeFormat = new IdFormat[BeamVehicleType]

  implicit val spaceTimeFormat = new Format[SpaceTime]
  implicit val streetVehicleFormat = new Format[StreetVehicle]
  implicit val householdAttributesFormat = new Format[HouseholdAttributes]
  implicit val attributesOfIndividualFormat = new Format[AttributesOfIndividual]
  implicit val intermodalUseFormat = new Format[IntermodalUse]
  implicit val routingRequestFormat = new Format[RoutingRequest]

  implicit val transitStopsInfoFormat = new Format[TransitStopsInfo]
  implicit val beamPathFormat = new Format[BeamPath]
  implicit val beamLegFormat = new Format[BeamLeg]
  implicit val embodiedBeamLegFormat = new Format[EmbodiedBeamLeg]
  implicit val embodiedBeamTripFormat = new Format[EmbodiedBeamTrip]
  implicit val routingResponseFormat = new Format[RoutingResponse]
}

class RoutingHandler(val workerRouter: ActorRef, val links: Map[Id[Link], Link], val streetLayer: StreetLayer)(implicit ex: ExecutionContext) extends FailFastCirceSupport {
  implicit val timeout: Timeout = new Timeout(10, TimeUnit.SECONDS)
  import AllNeededFormats._

  val geoUtils = new beam.sim.common.GeoUtils {
    override def localCRS: String = "epsg:26910"
  }

  val route: Route = {
    path("find-route") {
      post {
        entity(as[RoutingRequest]) { request =>
          val routingResponse = workerRouter.ask(request).mapTo[RoutingResponse]
          routingResponse.foreach { r =>
            val carLegs = r.itineraries.flatMap(_.legs).filter(leg => leg.beamLeg.mode == BeamMode.CAR)
            carLegs.foreach { leg =>
            {
              val linksWithCoordMatsim = leg.beamLeg.travelPath.linkIds.flatMap { id =>
                val linkId = Id.create(id, classOf[Link])
                links.get(linkId).map { link =>
                  link.getToNode().getOutLinks

                  val loc = link.asInstanceOf[BasicLocation[Link]]
                  val utmCoord = loc.getCoord
                  val wgsCoord = geoUtils.utm2Wgs.transform(utmCoord)
                  id -> wgsCoord
                }
              }
              val matsimGpxPoints = linksWithCoordMatsim.map { case (linkId, wgsCoord) => GpxPoint(linkId.toString, wgsCoord) }
              GpxWriter.write(s"${request.requestId}_matsim.gpx", matsimGpxPoints)
            }

            {
              val linksWithCoordR5 = leg.beamLeg.travelPath.linkIds.map { id =>
                val edge = streetLayer.edgeStore.getCursor(id)
                val coord = edge.getGeometry.getCoordinate
                id -> new Coord(coord.x, coord.y)
              }
              val r5GpxPoints = linksWithCoordR5.map { case (linkId, wgsCoord) => GpxPoint(linkId.toString, wgsCoord) }
              GpxWriter.write(s"${request.requestId}_r5.gpx", r5GpxPoints)
            }
            }
          }
          complete(routingResponse)
        }
      }
    }
  }
}

object CustomExceptionHandling extends LazyLogging {

  def handler: ExceptionHandler = ExceptionHandler {
    case t: Throwable =>
      extractClientIP { remoteAddress =>
        extractRequest { request =>
          val msg = s"Exception during processing $request from $remoteAddress: ${t.getMessage}"
          logger.error(msg, t)
          complete(HttpResponse(StatusCodes.InternalServerError, entity = msg))
        }
      }
  }
}

import scala.collection.JavaConverters._


object R5RoutingApp extends BeamHelper {
  import AllNeededFormats._

  implicit val actorSystem: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val timeout: Timeout = new Timeout(600, TimeUnit.SECONDS)

  def main(args: Array[String]): Unit = {
    val (arg, cfg) = prepareConfig(args, isConfigArgRequired = true)
    val workerRouter: ActorRef = actorSystem.actorOf(Props(classOf[R5RoutingWorker], cfg), name = "workerRouter")
    val f = Await.result(workerRouter ? Identify(0), Duration.Inf)
    logger.info("R5RoutingWorker is initialized!")

    val beamCfg = BeamConfig(cfg)
    val networkCoordinator = DefaultNetworkCoordinator(beamCfg)
    networkCoordinator.init()

    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network)
      .readFile(beamCfg.matsim.modules.network.inputNetworkFile)
    println(s"MATSim network file: ${beamCfg.matsim.modules.network.inputNetworkFile}")

    val links: Map[Id[Link], Link] = network.getLinks.asScala.toMap

    val interface = "0.0.0.0"
    val port = 9000
    val routingHandler = new RoutingHandler(workerRouter, links, networkCoordinator.transportNetwork.streetLayer)(scala.concurrent.ExecutionContext.Implicits.global)
    val boostedRoute = handleExceptions(CustomExceptionHandling.handler)(routingHandler.route)
    Http().bindAndHandle(boostedRoute, interface, port)
    logger.info(s"Http server is ready and bound to $interface:$port")
  }

  def printRoutingRequestJsonExample(): Unit = {
    val geoUtils = new beam.sim.common.GeoUtils {
      override def localCRS: String = "epsg:26910"
    }
    val startUTM = geoUtils.wgs2Utm(new Location(-122.4750527, 38.504534))
    val endUTM = geoUtils.wgs2Utm(new Location(-122.0371486, 37.37157))
    val departureTime = 20131
    val bodyStreetVehicle = StreetVehicle(
      Id.createVehicleId("1"),
      BeamVehicleType.defaultHumanBodyBeamVehicleType.id,
      new SpaceTime(startUTM, time = departureTime),
      CAR,
      asDriver = true
    )
    val routingRequest = RoutingRequest(
      originUTM = startUTM,
      destinationUTM = endUTM,
      departureTime = departureTime,
      transitModes = Vector.empty,
      streetVehicles = Vector(bodyStreetVehicle)
    )

    println(routingRequest.asJson.toString())
  }
}