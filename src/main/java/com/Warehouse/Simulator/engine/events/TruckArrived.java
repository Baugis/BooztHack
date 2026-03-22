package com.Warehouse.Simulator.engine.events;

import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.model.Shipment;
import java.util.ArrayList;
import java.util.List;

/**
 * EVENT: TruckArrived
 *
 * Fires once at each scheduled pull time for a given sorting direction.
 * When this event executes, every PACKED shipment assigned to that direction
 * is immediately marked SHIPPED and loaded onto the truck.
 *
 * On-time tracking:
 *   A shipment is "packed on time" if its packed timestamp is at or before
 *   this truck's pull time. Late shipments (packed after the pull time) are
 *   logged individually and counted separately for KPI reporting.
 *   In a healthy simulation late shipments should not occur, but the check
 *   is retained to surface any timing anomalies during analysis.
 *
 * Dwell time:
 *   For every shipped shipment the event also logs how long it sat in PACKED
 *   status before the truck collected it (shippedAt - packedAt).
 */
public class TruckArrived extends Event {

    /**
     * The sorting direction this truck services.
     * Only PACKED shipments whose {@code sortingDirection} matches this value
     * are loaded and marked SHIPPED.
     */
    private final String sortingDirection;

    /**
     * This truck's scheduled departure time, expressed as seconds elapsed
     * since the simulation epoch. Used as the deadline for on-time evaluation:
     * shipments packed at or before this value are counted as on time.
     */
    private final double pullTimeSecs;

    /**
     * Creates a TruckArrived event.
     *
     * @param simTime          simulation time at which the truck arrives (seconds from epoch)
     * @param sequenceNumber   tie-breaking sequence number for same-timestamp events
     * @param sortingDirection the direction identifier that selects which shipments to load
     * @param pullTimeSecs     the truck's pull time in seconds from epoch, used for on-time checks
     */
    public TruckArrived(double simTime, long sequenceNumber,
                        String sortingDirection, double pullTimeSecs) {
        super(simTime, sequenceNumber);
        this.sortingDirection = sortingDirection;
        this.pullTimeSecs     = pullTimeSecs;
    }

    /**
     * Executes the truck arrival:
     * <ol>
     *   <li>Scans all shipments for PACKED ones matching this truck's direction.</li>
     *   <li>Marks each matching shipment as SHIPPED.</li>
     *   <li>Classifies each as on-time or late based on its packed timestamp.</li>
     *   <li>Logs a per-truck summary and per-shipment dwell times.</li>
     * </ol>
     *
     * @param sim the running simulation context
     */
    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%s] TruckArrived: direction=%s%n",
                sim.getTimeLabel(), sortingDirection);

        List<Shipment> shipped = new ArrayList<>();
        int onTime = 0;
        int late   = 0;

        for (Shipment shipment : sim.getAllShipments()) {
            if (shipment.getStatus() == Shipment.ShipmentStatus.PACKED
                    && sortingDirection.equals(shipment.getSortingDirection())) {

                shipment.markAsShipped(sim.getCurrentTime());
                shipped.add(shipment);

                if (shipment.getPackedAt() <= pullTimeSecs) {
                    onTime++;
                } else {
                    late++;
                    System.out.printf(
                            "       LATE: %s (packed=%.0fs, pullTime=%.0fs, delta=+%.0fs)%n",
                            shipment.getId(),
                            shipment.getPackedAt(),
                            pullTimeSecs,
                            shipment.getPackedAt() - pullTimeSecs);
                }
            }
        }

        System.out.printf("[%s] Truck loaded %d shipments for direction=%s (onTime=%d, late=%d)%n",
                sim.getTimeLabel(), shipped.size(), sortingDirection, onTime, late);

        for (Shipment s : shipped) {
            double dwellTime = s.getShippedAt() - s.getPackedAt();
            System.out.printf("       Shipped: %s (dwell=%.0fs)%n", s.getId(), dwellTime);
        }
        
        java.util.Map<String, Object> logData = new java.util.HashMap<>();
        logData.put("sortingDirection", sortingDirection);
        logData.put("shippedCount", shipped.size());
        logData.put("onTime", onTime);
        logData.put("late", late);
        java.util.List<String> shippedIds = new java.util.ArrayList<>();
        for (Shipment s : shipped) shippedIds.add(s.getId());
        logData.put("shippedShipments", shippedIds);

sim.logEvent("TruckArrived", logData);
    }
}