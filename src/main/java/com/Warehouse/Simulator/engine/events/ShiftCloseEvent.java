package com.Warehouse.Simulator.engine.events;

import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.model.*;

/**
 * EVENT: ShiftCloseEvent
 *
 * Fires at the end time of a shift.
 * Calls requestClose() on every port in the shift's portConfig:
 *   - IDLE ports transition immediately to CLOSED.
 *   - BUSY ports transition to PENDING_CLOSE and finish their current shipment first.
 */
public class ShiftCloseEvent extends Event {

    private final String gridId;
    private final Shift shift;

    public ShiftCloseEvent(double simTime, long sequenceNumber, String gridId, Shift shift) {
        super(simTime, sequenceNumber);
        this.gridId = gridId;
        this.shift  = shift;
    }

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
                continue; // already closing
            }

            port.requestClose();

            if (port.getStatus() == Port.Status.CLOSED) {
                System.out.printf("[%s] Port %s closed (was IDLE)%n",
                        sim.getTimeLabel(), cfg.portId);
                // Drain any queued shipments back to the grid queue
                for (Shipment s : port.drainQueue()) {
                    grid.enqueueShipment(s);
                }
            } else {
                // PENDING_CLOSE — will close after current shipment finishes
                System.out.printf("[%s] Port %s -> PENDING_CLOSE (finishing current shipment)%n",
                        sim.getTimeLabel(), cfg.portId);
            }
        }
    }
}
