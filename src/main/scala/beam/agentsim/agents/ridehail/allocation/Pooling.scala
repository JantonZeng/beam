package beam.agentsim.agents.ridehail.allocation

import beam.agentsim.agents.ridehail.RideHailManager.{PoolingInfo}
import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.ridehail.{RideHailManager, RideHailRequest}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode.CAR
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

class Pooling(val rideHailManager: RideHailManager) extends RideHailResourceAllocationManager(rideHailManager) {

  val tempPickDropStore: mutable.Map[Int, PickDropIdAndLeg] = mutable.Map()

  override def respondToInquiry(inquiry: RideHailRequest): InquiryResponse = {
    rideHailManager.vehicleManager
      .getClosestIdleVehiclesWithinRadiusByETA(
        inquiry.pickUpLocationUTM,
        inquiry.destinationUTM,
        rideHailManager.radiusInMeters,
        inquiry.departAt
      ) match {
      case Some(agentETA) =>
        SingleOccupantQuoteAndPoolingInfo(agentETA.agentLocation, Some(PoolingInfo(1.1, 0.6)))
      case None =>
        NoVehiclesAvailable
    }
  }

  override def allocateVehiclesToCustomers(
    tick: Int,
    vehicleAllocationRequest: AllocationRequests
  ): AllocationResponse = {
    logger.info(s"buffer size: ${vehicleAllocationRequest.requests.size}")
    var toPool: Set[RideHailRequest] = Set()
    var notToPool: Set[RideHailRequest] = Set()
    var allocResponses: List[VehicleAllocation] = List()
    var alreadyAllocated: Set[Id[Vehicle]] = Set()
    vehicleAllocationRequest.requests.foreach {
      case (request, routingResponses) if routingResponses.isEmpty =>
        toPool += request
      case (request, _) =>
        notToPool += request
    }
    notToPool.foreach { request =>
      val routeResponses = vehicleAllocationRequest.requests(request)

      // First check for broken route responses (failed routing attempt)
      if (routeResponses.find(_.itineraries.isEmpty).isDefined) {
        allocResponses = allocResponses :+ NoVehicleAllocated(request)
      } else {
        // Make sure vehicle still available
        val vehicleId = routeResponses.head.itineraries.head.legs.head.beamVehicleId
        if (rideHailManager.vehicleManager.getIdleVehicles.contains(vehicleId) && !alreadyAllocated.contains(vehicleId)) {
          alreadyAllocated = alreadyAllocated + vehicleId
          val pickDropIdAndLegs = routeResponses.map { rResp =>
            tempPickDropStore
              .remove(rResp.requestId)
              .getOrElse(PickDropIdAndLeg(Some(request.customer), None))
              .copy(leg = rResp.itineraries.head.legs.headOption)
          }
          allocResponses = allocResponses :+ VehicleMatchedToCustomers(
            request,
            rideHailManager.vehicleManager.getIdleVehicles(vehicleId),
            pickDropIdAndLegs
          )
        } else {
          allocResponses = allocResponses :+ NoVehicleAllocated(request)
          request.groupedWithOtherRequests.foreach { req =>
            allocResponses = allocResponses :+ NoVehicleAllocated(req)
          }
        }
      }
    }
    toPool.grouped(2).foreach { twoToPool =>
      twoToPool.size match {
        case 1 =>
          Pooling.serveOneRequest(twoToPool.head, tick, alreadyAllocated, rideHailManager) match {
            case res @ RoutingRequiredToAllocateVehicle(_, routes) =>
              allocResponses = allocResponses :+ res
              alreadyAllocated = alreadyAllocated + routes.head.streetVehicles.head.id
            case res =>
              allocResponses = allocResponses :+ res
          }
        case 2 =>
          val request1 = twoToPool.head
          val routingResponses1 = vehicleAllocationRequest.requests(request1)
          val request2 = twoToPool.last
          val routingResponses2 = vehicleAllocationRequest.requests(request2)
          rideHailManager.vehicleManager
            .getClosestIdleVehiclesWithinRadiusByETA(
              request1.pickUpLocationUTM,
              request1.destinationUTM,
              rideHailManager.radiusInMeters,
              tick,
              excludeRideHailVehicles = alreadyAllocated
            ) match {
            case Some(agentETA) =>
              alreadyAllocated = alreadyAllocated + agentETA.agentLocation.vehicleId
              allocResponses = allocResponses :+ RoutingRequiredToAllocateVehicle(
                request1.addSubRequest(request2),
                createRoutingRequestsForPooledTrip(List(request1, request2), agentETA.agentLocation, tick)
              )
              // When we group request 2 with 1 we need to remove it from the buffer
              // so it won't be processed again (it's fate is now tied to request 1)
              removeRequestFromBuffer(request2)
            case None =>
              allocResponses = allocResponses :+ NoVehicleAllocated(request1)
              allocResponses = allocResponses :+ NoVehicleAllocated(request2)
          }
      }
    }
    VehicleAllocations(allocResponses)
  }

  def createRoutingRequestsForPooledTrip(
    requests: List[RideHailRequest],
    rideHailLocation: RideHailAgentLocation,
    tick: Int
  ): List[RoutingRequest] = {
    var routeReqs: List[RoutingRequest] = List()
    var startTime = tick
    var rideHailVehicleAtOrigin = StreetVehicle(
      rideHailLocation.vehicleId,
      rideHailLocation.vehicleTypeId,
      SpaceTime((rideHailLocation.currentLocationUTM.loc, startTime)),
      CAR,
      asDriver = false
    )

    // Pickups first
    requests.foreach { req =>
      val routeReq2Pickup = RoutingRequest(
        rideHailVehicleAtOrigin.locationUTM.loc,
        req.pickUpLocationUTM,
        startTime,
        withTransit = false,
        Vector(rideHailVehicleAtOrigin)
      )
      routeReqs = routeReqs :+ routeReq2Pickup
      tempPickDropStore.put(routeReq2Pickup.requestId, PickDropIdAndLeg(Some(req.customer), None))

      rideHailVehicleAtOrigin = StreetVehicle(
        rideHailLocation.vehicleId,
        rideHailLocation.vehicleTypeId,
        SpaceTime((req.pickUpLocationUTM, startTime)),
        CAR,
        asDriver = false
      )
    }

    // Dropoffs next
    requests.foreach { req =>
      val routeReq2Dropoff = RoutingRequest(
        rideHailVehicleAtOrigin.locationUTM.loc,
        req.destinationUTM,
        startTime,
        withTransit = false,
        Vector(rideHailVehicleAtOrigin)
      )
      routeReqs = routeReqs :+ routeReq2Dropoff
      tempPickDropStore.put(routeReq2Dropoff.requestId, PickDropIdAndLeg(Some(req.customer), None))

      rideHailVehicleAtOrigin = StreetVehicle(
        rideHailLocation.vehicleId,
        rideHailLocation.vehicleTypeId,
        SpaceTime((req.destinationUTM, startTime)),
        CAR,
        asDriver = false
      )
    }

    routeReqs
  }
//
//    // route from customer to destination
//    val rideHail2Destination = RoutingRequest(
//      request.pickUpLocation,
//      request.destination,
//      requestTime,
//      Vector(),
//      Vector(rideHailVehicleAtPickup)
//    )
//
//    List(rideHailAgent2Customer, rideHail2Destination)
//  }

}

object Pooling {

  def serveOneRequest(
    request: RideHailRequest,
    pickUpTime: Int,
    alreadyAllocated: Set[Id[Vehicle]],
    rideHailManager: RideHailManager
  ) = {
    rideHailManager.vehicleManager
      .getClosestIdleVehiclesWithinRadiusByETA(
        request.pickUpLocationUTM,
        request.destinationUTM,
        rideHailManager.radiusInMeters,
        pickUpTime,
        excludeRideHailVehicles = alreadyAllocated
      ) match {
      case Some(agentETA) =>
        RoutingRequiredToAllocateVehicle(
          request,
          rideHailManager.createRoutingRequestsToCustomerAndDestination(
            pickUpTime,
            request,
            agentETA.agentLocation
          )
        )
      case None =>
        NoVehicleAllocated(request)
    }
  }

}
