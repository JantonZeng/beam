package beam.sim
import java.awt.Color
import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

import akka.actor.Status.{Failure, Success}
import akka.actor.SupervisorStrategy.Stop
import akka.actor.{
  Actor,
  ActorLogging,
  ActorRef,
  Cancellable,
  DeadLetter,
  Identify,
  OneForOneStrategy,
  PoisonPill,
  Props,
  SupervisorStrategy,
  Terminated
}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.BeamVehicleStateUpdate
import beam.agentsim.agents.ridehail.RideHailManager.{
  BufferedRideHailRequestsTimeout,
  NotifyIterationEnds,
  RideHailAllocationManagerTimeout
}
import beam.agentsim.agents.ridehail.{RideHailAgent, RideHailManager, RideHailSurgePricingManager}
import beam.agentsim.agents.vehicles.BeamVehicleType.HumanBodyVehicle
import beam.agentsim.agents.{BeamAgent, InitializeTrigger, Population}
import beam.agentsim.infrastructure.ParkingManager.ParkingStockAttributes
import beam.agentsim.infrastructure.ZonalParkingManager
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, StartSchedule}
import beam.router.BeamRouter.InitTransit
import beam.sim.metrics.MetricsSupport
import beam.sim.monitoring.ErrorListener
import beam.utils._
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.utils.misc.Time
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.duration.{Deadline, _}

class IterationActor(
  val beamServices: BeamServices,
  val transportNetwork: TransportNetwork,
  val scenario: Scenario,
  val eventsManager: EventsManager,
  val rideHailSurgePricingManager: RideHailSurgePricingManager
) extends Actor
    with ActorLogging
    with MetricsSupport {

  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  val rideHailAgents: ArrayBuffer[ActorRef] = new ArrayBuffer()

  val rideHailHouseholds: mutable.Set[Id[Household]] =
    mutable.Set[Id[Household]]()

  val startTime = Deadline.now
  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 1) {
      // Yes, we just stop watching actor because unhandled exception there is something critical!
      case _: Exception => Stop
    }
  var runSender: ActorRef = _

  private val errorListener = context.actorOf(ErrorListener.props())
  context.watch(errorListener)
  context.system.eventStream.subscribe(errorListener, classOf[BeamAgent.TerminatedPrematurelyEvent])

  private val scheduler = context.actorOf(
    Props(
      classOf[BeamAgentScheduler],
      beamServices.beamConfig,
      Time.parseTime(beamServices.beamConfig.matsim.modules.qsim.endTime),
      beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow,
      new StuckFinder(beamServices.beamConfig.beam.debug.stuckAgentDetection)
    ),
    "scheduler"
  )
  context.system.eventStream.subscribe(errorListener, classOf[DeadLetter])
  context.watch(scheduler)

  private val envelopeInUTM = beamServices.geo.wgs2Utm(transportNetwork.streetLayer.envelope)
  envelopeInUTM.expandBy(beamServices.beamConfig.beam.spatial.boundingBoxBuffer)

  private val parkingManager = context.actorOf(
    ZonalParkingManager.props(beamServices, beamServices.beamRouter, ParkingStockAttributes(100)),
    "ParkingManager"
  )
  context.watch(parkingManager)

  private val rideHailManager = context.actorOf(
    RideHailManager.props(
      beamServices,
      scheduler,
      beamServices.beamRouter,
      parkingManager,
      envelopeInUTM,
      rideHailSurgePricingManager
    ),
    "RideHailManager"
  )
  context.watch(rideHailManager)

  if (beamServices.beamConfig.beam.agentsim.agents.rideHail.refuelThresholdInMeters >= beamServices.beamConfig.beam.agentsim.agents.rideHail.vehicleRangeInMeters * 0.8) {
    log.error(
      "Ride Hail refuel threshold is higher than state of energy of a vehicle fueled by a DC fast charger. This will cause an infinite loop"
    )
  }

  val (debugActorWithTimerActorRef: Option[ActorRef], debugActorWithTimerCancellable: Option[Cancellable]) =
    if (beamServices.beamConfig.beam.debug.debugActorTimerIntervalInSec > 0) {
      val debugActorWithTimer = context.actorOf(Props(classOf[DebugActorWithTimer], rideHailManager, scheduler))
      val cancellable = prepareMemoryLoggingTimerActor(
        beamServices.beamConfig.beam.debug.debugActorTimerIntervalInSec,
        debugActorWithTimer
      )
      (Some(debugActorWithTimer), Some(cancellable))
    } else {
      (None, None)
    }

  private val population = context.actorOf(
    Population.props(
      scenario,
      beamServices,
      scheduler,
      transportNetwork,
      beamServices.beamRouter,
      rideHailManager,
      parkingManager,
      eventsManager
    ),
    "population"
  )
  context.watch(population)
  Await.result(population ? Identify(0), timeout.duration)

  private val numRideHailAgents = math.round(
    scenario.getPopulation.getPersons.size * beamServices.beamConfig.beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation
  )
  private val rideHailVehicleType = BeamVehicleUtils
    .getVehicleTypeById(
      beamServices.beamConfig.beam.agentsim.agents.rideHail.vehicleTypeId,
      scenario.getVehicles.getVehicleTypes
    )
    .getOrElse(scenario.getVehicles.getVehicleTypes.get(Id.create("1", classOf[VehicleType])))

  val quadTreeBounds: QuadTreeBounds = getQuadTreeBound(
    scenario.getPopulation.getPersons
      .values()
      .stream()
  )

  val rand: Random =
    new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)

  val rideHailinitialLocationSpatialPlot = new SpatialPlot(1100, 1100, 50)
  val activityLocationsSpatialPlot = new SpatialPlot(1100, 1100, 50)

  if (beamServices.matsimServices != null) {

    scenario.getPopulation.getPersons
      .values()
      .forEach(
        x =>
          x.getSelectedPlan.getPlanElements.forEach {
            case z: Activity =>
              activityLocationsSpatialPlot.addPoint(PointToPlot(z.getCoord, Color.RED, 10))
            case _ =>
        }
      )

    scenario.getPopulation.getPersons
      .values()
      .forEach(x => {
        val personInitialLocation: Coord =
          x.getSelectedPlan.getPlanElements
            .iterator()
            .next()
            .asInstanceOf[Activity]
            .getCoord
        activityLocationsSpatialPlot
          .addPoint(PointToPlot(personInitialLocation, Color.BLUE, 10))
      })

    activityLocationsSpatialPlot.writeImage(
      beamServices.matsimServices.getControlerIO
        .getIterationFilename(beamServices.iterationNumber, "activityLocations.png")
    )
  }

  val persons: Iterable[Person] = RandomUtils.shuffle(scenario.getPopulation.getPersons.values().asScala, rand)
  persons.view.take(numRideHailAgents.toInt).foreach { person =>
    val personInitialLocation: Coord =
      person.getSelectedPlan.getPlanElements
        .iterator()
        .next()
        .asInstanceOf[Activity]
        .getCoord
    val rideInitialLocation: Coord =
      beamServices.beamConfig.beam.agentsim.agents.rideHail.initialLocation.name match {
        case RideHailManager.INITIAL_RIDEHAIL_LOCATION_HOME =>
          val radius =
            beamServices.beamConfig.beam.agentsim.agents.rideHail.initialLocation.home.radiusInMeters
          new Coord(
            personInitialLocation.getX + radius * (rand.nextDouble() - 0.5),
            personInitialLocation.getY + radius * (rand.nextDouble() - 0.5)
          )
        case RideHailManager.INITIAL_RIDEHAIL_LOCATION_UNIFORM_RANDOM =>
          val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx) * rand
            .nextDouble()
          val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny) * rand
            .nextDouble()
          new Coord(x, y)
        case RideHailManager.INITIAL_RIDEHAIL_LOCATION_ALL_AT_CENTER =>
          val x = quadTreeBounds.minx + (quadTreeBounds.maxx - quadTreeBounds.minx) / 2
          val y = quadTreeBounds.miny + (quadTreeBounds.maxy - quadTreeBounds.miny) / 2
          new Coord(x, y)
        case RideHailManager.INITIAL_RIDEHAIL_LOCATION_ALL_IN_CORNER =>
          val x = quadTreeBounds.minx
          val y = quadTreeBounds.miny
          new Coord(x, y)
        case unknown =>
          log.error(s"unknown rideHail.initialLocation $unknown")
          null
      }

    val rideHailName = s"rideHailAgent-${person.getId}"

    val rideHailVehicleId =
      Id.createVehicleId(s"rideHailVehicle-${person.getId}")
    val rideHailVehicle: Vehicle =
      VehicleUtils.getFactory.createVehicle(rideHailVehicleId, rideHailVehicleType)
    val rideHailAgentPersonId: Id[RideHailAgent] =
      Id.create(rideHailName, classOf[RideHailAgent])
    val engineInformation =
      Option(rideHailVehicle.getType.getEngineInformation)
    val vehicleAttribute =
      Option(scenario.getVehicles.getVehicleAttributes)
    val rideHailBeamVehicle = BeamVehicleUtils.makeCar(
      rideHailVehicle,
      beamServices.beamConfig.beam.agentsim.agents.rideHail.vehicleRangeInMeters,
      None
    )

    beamServices.vehicles += (rideHailVehicleId -> rideHailBeamVehicle)
    rideHailBeamVehicle.registerResource(rideHailManager)
    rideHailManager ! BeamVehicleStateUpdate(
      rideHailBeamVehicle.getId,
      rideHailBeamVehicle.getState()
    )
    val rideHailAgentProps = RideHailAgent.props(
      beamServices,
      scheduler,
      transportNetwork,
      eventsManager,
      parkingManager,
      rideHailAgentPersonId,
      rideHailBeamVehicle,
      rideInitialLocation
    )
    val rideHailAgentRef: ActorRef =
      context.actorOf(rideHailAgentProps, rideHailName)
//    context.watch(rideHailAgentRef)
    scheduler ! ScheduleTrigger(InitializeTrigger(0.0), rideHailAgentRef)
    rideHailAgents += rideHailAgentRef

    rideHailinitialLocationSpatialPlot
      .addString(StringToPlot(s"${person.getId}", rideInitialLocation, Color.RED, 20))
    rideHailinitialLocationSpatialPlot
      .addAgentWithCoord(
        RideHailAgentInitCoord(rideHailAgentPersonId, rideInitialLocation)
      )
  }

  if (beamServices.matsimServices != null) {
    rideHailinitialLocationSpatialPlot.writeCSV(
      beamServices.matsimServices.getControlerIO
        .getIterationFilename(beamServices.iterationNumber, "rideHailInitialLocation.csv")
    )
    rideHailinitialLocationSpatialPlot.writeImage(
      beamServices.matsimServices.getControlerIO
        .getIterationFilename(beamServices.iterationNumber, "rideHailInitialLocation.png")
    )
  }
  log.info(s"Initialized ${beamServices.personRefs.size} people")
  log.info(s"Initialized ${scenario.getVehicles.getVehicles.size()} personal vehicles")
  log.info(s"Initialized $numRideHailAgents ride hailing agents")

  Await.result(beamServices.beamRouter ? InitTransit(scheduler, parkingManager), timeout.duration)

  if (beamServices.iterationNumber == 0)
    new BeamWarmStart(beamServices).init()

  log.info(s"Transit schedule has been initialized")

  scheduleRideHailManagerTimerMessages()

  def prepareMemoryLoggingTimerActor(timeoutInSeconds: Int, actor: ActorRef): Cancellable = {
    context.system.scheduler.schedule(0.milliseconds, (timeoutInSeconds * 1000).milliseconds, actor, Tick)(
      context.dispatcher
    )
  }

  override def receive: PartialFunction[Any, Unit] = {

    case CompletionNotice(_, _) =>
      log.info("Scheduler is finished.")
      endSegment("agentsim-execution", "agentsim")
      log.info("Ending Agentsim")
      log.info("Processing Agentsim Events (Start)")
      startSegment("agentsim-events", "agentsim")

      cleanupRideHailingAgents()
      cleanupVehicle()

      context.unwatch(population)
      population ! Finish

      val future = rideHailManager.ask(NotifyIterationEnds())
      Await.ready(future, timeout.duration).value
      context.unwatch(rideHailManager)
      context.stop(rideHailManager)

      context.unwatch(scheduler)
      context.stop(scheduler)

      context.unwatch(errorListener)
      context.stop(errorListener)

      context.unwatch(parkingManager)
      context.stop(parkingManager)

      debugActorWithTimerCancellable.foreach(_.cancel())
      debugActorWithTimerActorRef.foreach { actor =>
        context.unwatch(actor)
        context.stop(actor)
      }

      log.info("Stopped all actors")
      val msg =
        s"Mobsim iteration ${beamServices.iterationNumber} has been finished in ${(Deadline.now - startTime).toMillis} ms"
      runSender ! Success(msg)
    case Terminated(who) =>
      log.error("Terminated: {}", who)
      if (context.children.isEmpty) {
        context.stop(self)
        runSender ! Success("Ran.")
      } else {
        log.error("Remaining: {}", context.children)
        // We send back failure
        runSender ! Failure(new IllegalStateException(s"One of the watching actors '$who' has been terminated."))
        // And kill ourself :(
        self ! PoisonPill
      }

    case "Run!" =>
      runSender = sender
      log.info("Running BEAM Mobsim")
      endSegment("iteration-preparation", "mobsim")

      log.info("Preparing new Iteration (End)")
      log.info("Starting Agentsim")
      startSegment("agentsim-execution", "agentsim")

      scheduler ! StartSchedule(beamServices.iterationNumber)
  }

  private def scheduleRideHailManagerTimerMessages(): Unit = {
    val timerTrigger = RideHailAllocationManagerTimeout(0.0)
    val timerMessage = ScheduleTrigger(timerTrigger, rideHailManager)
    scheduler ! timerMessage

    scheduler ! ScheduleTrigger(BufferedRideHailRequestsTimeout(0.0), rideHailManager)
    log.info(s"rideHailManagerTimerScheduled")
  }

  private def cleanupRideHailingAgents(): Unit = {
    rideHailAgents.foreach { actor =>
      actor ! Finish
//      context.unwatch(actor)
    }
    rideHailAgents.clear()
  }

  private def cleanupVehicle(): Unit = {
    // FIXME XXXX (VR): Probably no longer necessarylog.info(s"Removing Humanbody vehicles")
    scenario.getPopulation.getPersons.keySet().forEach { personId =>
      val bodyVehicleId = HumanBodyVehicle.createId(personId)
      beamServices.vehicles -= bodyVehicleId
    }
  }

  def getQuadTreeBound[p <: Person](persons: Stream[p]): QuadTreeBounds = {
    // TODO
    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue
    persons.forEach { person =>
      val planElementsIterator = person.getSelectedPlan.getPlanElements.iterator()
      while (planElementsIterator.hasNext) {
        val planElement = planElementsIterator.next()
        planElement match {
          case activity: Activity =>
            val coord = activity.getCoord
            minX = if (minX > coord.getX) coord.getX else minX
            maxX = if (maxX < coord.getX) coord.getX else maxX
            minY = if (minY > coord.getY) coord.getY else minY
            maxY = if (maxY < coord.getY) coord.getY else maxY
          case _ =>
        }
      }
    }

    QuadTreeBounds(minX, minY, maxX, maxY)
  }
}

object IterationActor {

  def props(
    beamServices: BeamServices,
    transportNetwork: TransportNetwork,
    scenario: Scenario,
    eventsManager: EventsManager,
    rideHailSurgePricingManager: RideHailSurgePricingManager
  ): Props = {
    Props(classOf[IterationActor], beamServices, transportNetwork, scenario,
      eventsManager, rideHailSurgePricingManager)
  }
}