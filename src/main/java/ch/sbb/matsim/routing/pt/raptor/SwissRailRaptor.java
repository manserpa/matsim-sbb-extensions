/*
 * Copyright (C) Schweizerische Bundesbahnen SBB, 2017.
 */

package ch.sbb.matsim.routing.pt.raptor;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.GenericRouteFactory;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.facilities.Facility;
import org.matsim.pt.router.TransitRouter;
import org.matsim.pt.routes.ExperimentalTransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides public transport route search capabilities using an implementation of the
 * RAPTOR algorithm underneath.
 *
 * @author mrieser / SBB
 */
public class SwissRailRaptor implements TransitRouter {

    private static final Logger log = Logger.getLogger(SwissRailRaptor.class);

    private final SwissRailRaptorData data;
    private final SwissRailRaptorCore raptor;
    private final RaptorConfig config;

    public SwissRailRaptor(final SwissRailRaptorData data) {
        this.data = data;
        this.config = data.config;
        this.raptor = new SwissRailRaptorCore(data);
    }

    @Override
    public List<Leg> calcRoute(Facility<?> fromFacility, Facility<?> toFacility, double departureTime, Person person) {
        List<InitialStop> accessStops = findAccessStops(fromFacility, person);
        List<InitialStop> egressStops = findEgressStops(toFacility, person);

        RaptorRoute foundRoute = this.raptor.calcLeastCostRoute(departureTime, accessStops, egressStops);
        RaptorRoute directWalk = createDirectWalk(fromFacility, toFacility, departureTime, person);

        if (foundRoute == null || directWalk.totalCosts < foundRoute.totalCosts) {
            foundRoute = directWalk;
        }
        List<Leg> legs = convertRouteToLegs(foundRoute);
        return legs;
    }

    private List<InitialStop> findAccessStops(Facility<?> facility, Person person) {
        List<TransitStopFacility> stops = findNearbyStops(facility);
        List<InitialStop> initialStops = stops.stream().map(stop -> {
            double beelineDistance = CoordUtils.calcEuclideanDistance(stop.getCoord(), facility.getCoord());
            double travelTime = beelineDistance / this.config.getBeelineWalkSpeed();
            double disutility = travelTime * this.config.getMarginalUtilityOfTravelTimeAccessWalk_utl_s();
            return new InitialStop(stop, -disutility, travelTime, TransportMode.transit_walk);
        }).collect(Collectors.toList());
        return initialStops;
    }

    private List<InitialStop> findEgressStops(Facility<?> facility, Person person) {
        List<TransitStopFacility> stops = findNearbyStops(facility);
        List<InitialStop> initialStops = stops.stream().map(stop -> {
            double beelineDistance = CoordUtils.calcEuclideanDistance(stop.getCoord(), facility.getCoord());
            double travelTime = beelineDistance / this.config.getBeelineWalkSpeed();
            double disutility = travelTime * this.config.getMarginalUtilityOfTravelTimeEgressWalk_utl_s();
            return new InitialStop(stop, -disutility, travelTime, TransportMode.transit_walk);
        }).collect(Collectors.toList());
        return initialStops;
    }

    private List<TransitStopFacility> findNearbyStops(Facility<?> facility) {
        double x = facility.getCoord().getX();
        double y = facility.getCoord().getY();
        Collection<TransitStopFacility> stopFacilities = this.data.stopsQT.getDisk(x, y, this.config.getSearchRadius());
        if (stopFacilities.size() < 2) {
            TransitStopFacility  nearestStop = this.data.stopsQT.getClosest(x, y);
            double nearestDistance = CoordUtils.calcEuclideanDistance(facility.getCoord(), nearestStop.getCoord());
            stopFacilities = this.data.stopsQT.getDisk(x, y, nearestDistance + this.config.getExtensionRadius());
        }
        if (stopFacilities instanceof List) {
            return (List<TransitStopFacility>) stopFacilities;
        }
        return new ArrayList<>(stopFacilities);
    }

    private RaptorRoute createDirectWalk(Facility<?> fromFacility, Facility<?> toFacility, double departureTime, Person person) {
        double beelineDistance = CoordUtils.calcEuclideanDistance(fromFacility.getCoord(), toFacility.getCoord());
        double walkTime = beelineDistance / this.config.getBeelineWalkSpeed();
        double walkCost_per_s = -this.config.getMarginalUtilityOfTravelTimeWalk_utl_s();
        double walkCost = walkTime * walkCost_per_s;

        RaptorRoute route = new RaptorRoute(fromFacility, toFacility, walkCost);
        route.addNonPt(null, null, departureTime, walkTime, TransportMode.transit_walk);
        return route;
    }

    private List<Leg> convertRouteToLegs(RaptorRoute route) {
        List<Leg> legs = new ArrayList<>(route.parts.size());
        for (RaptorRoute.RoutePart part : route.parts) {
            if (part.line != null) {
                // a pt leg
                Leg ptLeg = PopulationUtils.createLeg(part.mode);
                ptLeg.setDepartureTime(part.depTime);
                ptLeg.setTravelTime(part.travelTime);
                ExperimentalTransitRoute ptRoute = new ExperimentalTransitRoute(part.fromStop, part.line, part.route, part.toStop);
                ptRoute.setTravelTime(part.travelTime);
                ptLeg.setRoute(ptRoute);
                legs.add(ptLeg);
            } else {
                // a non-pt leg
                Leg walkLeg = PopulationUtils.createLeg(part.mode);
                walkLeg.setDepartureTime(part.depTime);
                walkLeg.setTravelTime(part.travelTime);
                Id<Link> startLinkId = part.fromStop == null ? null : part.fromStop.getLinkId();
                Id<Link> endLinkId =  part.toStop == null ? null : part.toStop.getLinkId();
//                Id<Link> startLinkId = part.fromStop == null ? route.fromFacility.getLinkId() : part.fromStop.getLinkId();
//                Id<Link> endLinkId =  part.toStop == null ? route.toFacility.getLinkId() : part.toStop.getLinkId();
                Route walkRoute = new GenericRouteFactory().createRoute(startLinkId, endLinkId);
                walkRoute.setTravelTime(part.travelTime);
                walkLeg.setRoute(walkRoute);
                legs.add(walkLeg);
            }
        }

        legs = fixLegs(legs);

        return legs;
    }

    private static int warnCounter = 0;
    static List<Leg> fixLegs(List<Leg> sourceLegs) {
        boolean needsFixing = false;
        int walkLegCount = 0;
        for (Leg leg : sourceLegs) {
            if (isWalkLeg(leg)) {
                walkLegCount++;
                if (walkLegCount > 1) {
                    needsFixing = true;
                    break;
                }
            } else {
                walkLegCount = 0;
            }
        }
        if (!needsFixing) {
            return sourceLegs;
        }
        if (warnCounter < 50) {
            warnCounter++;
            StringBuilder info = new StringBuilder();
            for (Leg leg : sourceLegs) {
                info.append("\n    " + leg.toString());
            }
            log.error("SwissRailRaptor result needs fixing: " + info.toString());
        }
        List<Leg> fixedLegs = new ArrayList<>();
        Leg prevLeg = null;
        boolean prevLegIsWalk = false;
        for (Leg leg : sourceLegs) {
            boolean isWalkLeg = isWalkLeg(leg);
            if (isWalkLeg) {
                if (prevLeg != null) {
                    if (prevLegIsWalk) {
                        // both the last and this leg are walk legs, merge them
                        String mode = TransportMode.transit_walk;
                        if (prevLeg.getMode().equals(TransportMode.access_walk)) {
                            mode = TransportMode.access_walk;
                        }
                        if (leg.getMode().equals(TransportMode.egress_walk)) {
                            mode = TransportMode.egress_walk;
                        }
                        Leg mergedLeg = PopulationUtils.createLeg(mode);
                        mergedLeg.setDepartureTime(prevLeg.getDepartureTime());
                        mergedLeg.setTravelTime(prevLeg.getTravelTime() + leg.getTravelTime());
                        Route prevRoute = prevLeg.getRoute();
                        Route thisRoute = leg.getRoute();
                        
                        Route mergedRoute = new GenericRouteFactory().createRoute(prevRoute.getStartLinkId(), thisRoute.getEndLinkId());
                        mergedRoute.setDistance(prevRoute.getDistance() + thisRoute.getDistance());
                        mergedRoute.setTravelTime(prevRoute.getTravelTime() + thisRoute.getTravelTime());
                        mergedLeg.setRoute(mergedRoute);
                        leg = mergedLeg; // don't add it, but make sure the mergedLeg becomes the next prevLeg.
                    } else {
                        fixedLegs.add(prevLeg);
                    }
                }
            } else {
                if (prevLeg != null) {
                    fixedLegs.add(prevLeg);
                }
            }
            prevLegIsWalk = isWalkLeg;
            prevLeg = leg;
        }
        if (prevLeg != null) {
            fixedLegs.add(prevLeg);
        }
        return fixedLegs;
    }

    private static boolean isWalkLeg(Leg leg) {
        String mode = leg.getMode();
        return mode.equals(TransportMode.walk) || mode.equals(TransportMode.transit_walk)
                || mode.equals(TransportMode.access_walk) || mode.equals(TransportMode.egress_walk);
        }

}
