package com.Warehouse.Simulator.engine.events;

import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.model.*;

/**
 * EVENT: ShiftCloseEvent
 *
 * Fires at the end time of a shift for a grid.
 * Iterates over every port in the shift's portConfig and requests each one
 * to close, respecting the port's current workload:
 *
 *   - IDLE ports close immediately (CLOSED). Any shipments still waiting in
 *     the port's local queue are returned to the grid queue so they can be
 *     picked up by another port or re-routed in the next router cycle.
 *   - BUSY ports transition to PENDING_CLOSE and finish packing the shipment
 *     they are currently working on before closing.
 *   - Ports already CLOSED or PENDING_CLOSE are skipped silently.
 *
 * Scheduled by ShiftOpenEvent at shift start, using the shift end time as
 * the absolute simulation trigger time.
 */
public class ShiftCloseEvent extends Event {

    /** ID of the grid whose ports this event closes. */
    private final String gridId;

    /** The shift definition that is ending; used to identify which ports to close. */
    private final Shift shift;

    /**
     * Creates a ShiftCloseEvent.
     *
     * @param simTime        simulation time at which the shift ends (seconds from epoch)
     * @param sequenceNumber tie-breaking sequence number for same-timestamp events
     * @param gridId         ID of the grid whose ports should be closed
     * @param shift          the shift definition that is ending
     */
    public ShiftCloseEvent(double simTime, long sequenceNumber, String gridId, Shift shift) {
        super(simTime, sequenceNumber);
        this.gridId = gridId;
        this.shift  = shift;
    }

    /**
     * Closes or begins closing every port belonging to this shift (see class Javadoc).
     * Shipments drained from IDLE ports' queues are returned to the grid queue
     * so no work is lost.
     *
     * @param sim the running simulation context
     */
    @Override
    public void execute(Simulation sim) {
        Grid grid = sim.getGrid(gridId);
        if (grid == null) {
            System.err.println("ShiftCloseEvent: unknown grid " + gridId);
            return;
        }

        System.out.printf("[%s] ShiftClose: grid=%s shift=%s-%s%n",
                sim.getTimeLabel(), gridId, shift.getStartAt(), shift.getEndAt());

        for (Shift.PortConfig cfg : shift.portConfig) {
            Port port = grid.getPort(cfg.portId);
            if (port == null) continue;

            Port.Status status = port.getStatus();

            if (status == Port.Status.CLOSED || status == Port.Status.PENDING_CLOSE) {
                continue;
            }

            port.requestClose();

            if (port.getStatus() == Port.Status.CLOSED) {
                System.out.printf("[%s] Port %s closed (was IDLE)%n",
                        sim.getTimeLabel(), cfg.portId);
                for (Shipment s : port.drainQueue()) {
                    grid.enqueueShipment(s);
                }
            } else {
                System.out.printf("[%s] Port %s -> PENDING_CLOSE (finishing current shipment)%n",
                        sim.getTimeLabel(), cfg.portId);
            }
        }
        java.util.Map<String, Object> logData = new java.util.HashMap<>();
        logData.put("gridId", gridId);
        sim.logEvent("ShiftClose", logData);
    }
}