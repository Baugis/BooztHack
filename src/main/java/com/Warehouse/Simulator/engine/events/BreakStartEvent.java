package com.Warehouse.Simulator.engine.events;

import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.model.Grid;
import com.Warehouse.Simulator.model.Port;
import com.Warehouse.Simulator.model.Shift;
import com.Warehouse.Simulator.model.Shipment;

/**
 * EVENT: BreakStartEvent
 *
 * Fires at the start of a scheduled break window for a grid's shift.
 * Behaves like a mini shift-close: ports are taken offline gracefully so
 * in-progress work is not lost.
 *
 * <p>Per-port behaviour:
 * <ul>
 *   <li><b>IDLE</b> → {@link Port#requestClose()} transitions immediately to
 *       {@link Port.Status#CLOSED}. The port's queued shipments are drained
 *       back to the grid queue so {@link BreakEndEvent} can reassign them.</li>
 *   <li><b>BUSY</b> → {@link Port#requestClose()} transitions to
 *       {@link Port.Status#PENDING_CLOSE}. The port finishes its current
 *       shipment, then closes on its own without accepting new work.</li>
 *   <li><b>CLOSED / PENDING_CLOSE</b> → already offline; skipped.</li>
 * </ul>
 */
public class BreakStartEvent extends Event {

    /** Grid whose ports are being paused for the break. */
    private final String gridId;

    /** Shift that owns this break window; used to identify which ports to close. */
    private final Shift shift;

    /** The specific break window that is starting. */
    private final Shift.BreakWindow breakWindow;

    /**
     * @param simTime        simulation time at which the break starts
     * @param sequenceNumber tie-breaker for same-timestamp events
     * @param gridId         grid whose break is starting
     * @param shift          shift that scheduled this break
     * @param breakWindow    break window that has just begun
     */
    public BreakStartEvent(double simTime, long sequenceNumber,
                           String gridId, Shift shift, Shift.BreakWindow breakWindow) {
        super(simTime, sequenceNumber);
        this.gridId      = gridId;
        this.shift       = shift;
        this.breakWindow = breakWindow;
    }

    /**
     * Gracefully closes all active ports for this shift.
     *
     * <p>Iterates the shift's port configs and calls {@link Port#requestClose()}
     * on each port that is not already offline. The resulting status determines
     * whether the port closes immediately or defers until its shipment finishes.
     * Queued shipments on immediately-closed ports are returned to the grid so
     * they are not stranded during the break.
     *
     * @param sim the running {@link Simulation} instance
     */
    @Override
    public void execute(Simulation sim) {
        Grid grid = sim.getGrid(gridId);
        if (grid == null) {
            System.err.println("BreakStartEvent: unknown grid " + gridId);
            return;
        }

        System.out.printf("[%s] BreakStart: grid=%s break=%s-%s%n",
                sim.getTimeLabel(), gridId, breakWindow.startAt, breakWindow.endAt);

        for (Shift.PortConfig cfg : shift.portConfig) {
            Port port = grid.getPort(cfg.portId);
            if (port == null) continue;

            Port.Status status = port.getStatus();
            if (status == Port.Status.CLOSED || status == Port.Status.PENDING_CLOSE) {
                continue;
            }

            port.requestClose();

            if (port.getStatus() == Port.Status.CLOSED) {
                System.out.printf("[%s] Port %s paused for break%n",
                        sim.getTimeLabel(), cfg.portId);
                for (Shipment s : port.drainQueue()) {
                    grid.enqueueShipment(s);
                }
            } else {
                System.out.printf("[%s] Port %s -> PENDING_CLOSE for break%n",
                        sim.getTimeLabel(), cfg.portId);
            }
        }
    }
}