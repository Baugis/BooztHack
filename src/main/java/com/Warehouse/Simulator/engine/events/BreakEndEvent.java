package com.Warehouse.Simulator.engine.events;

import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.model.Grid;
import com.Warehouse.Simulator.model.Port;
import com.Warehouse.Simulator.model.Shift;
import com.Warehouse.Simulator.model.Shipment;
import com.Warehouse.Simulator.model.Bin;
import com.Warehouse.Simulator.router.RouterDTOs;


/**
 * EVENT: BreakEndEvent
 *
 * Fires at the end of a scheduled break window for a grid's shift.
 * Re-opens any CLOSED ports and immediately tries to pull waiting shipments
 * from the grid queue so picking resumes without manual intervention.
 *
 * <p>Only ports in {@link Port.Status#CLOSED} state are re-opened. Ports in
 * {@link Port.Status#PENDING_CLOSE} are still finishing their last pre-break
 * shipment and are left untouched — they will become IDLE on their own once
 * that shipment completes.
 */
public class BreakEndEvent extends Event {

    /** Grid whose ports are coming back from break. */
    private final String gridId;

    /** Shift that owns this break window; used to know which ports to re-open. */
    private final Shift shift;

    /** The specific break window that has just ended. */
    private final Shift.BreakWindow breakWindow;

    /**
     * @param simTime        simulation time at which the break ends
     * @param sequenceNumber tie-breaker for same-timestamp events
     * @param gridId         grid whose break is ending
     * @param shift          shift that scheduled this break
     * @param breakWindow    break window that has just finished
     */
    public BreakEndEvent(double simTime, long sequenceNumber,
                         String gridId, Shift shift, Shift.BreakWindow breakWindow) {
        super(simTime, sequenceNumber);
        this.gridId      = gridId;
        this.shift       = shift;
        this.breakWindow = breakWindow;
    }

    /**
     * Re-opens CLOSED ports and kicks off the next queued shipment on each.
     *
     * <p>For every port listed in the shift's {@link Shift#portConfig}:
     * <ul>
     *   <li>If {@link Port.Status#CLOSED} → open the port, then attempt to
     *       dequeue and start one shipment from the grid queue.</li>
     *   <li>If the dequeued shipment cannot be handled by this port (flag
     *       mismatch or capacity full), it is re-enqueued so it isn't lost.</li>
     *   <li>If {@link Port.Status#PENDING_CLOSE} or any other status → skip;
     *       those ports manage their own lifecycle.</li>
     * </ul>
     *
     * @param sim the running {@link Simulation} instance
     */
    @Override
    public void execute(Simulation sim) {
        Grid grid = sim.getGrid(gridId);
        if (grid == null) {
            System.err.println("BreakEndEvent: unknown grid " + gridId);
            return;
        }

        System.out.printf("[%s] BreakEnd: grid=%s break=%s-%s%n",
                sim.getTimeLabel(), gridId, breakWindow.startAt, breakWindow.endAt);

        for (Shift.PortConfig cfg : shift.portConfig) {
            Port port = grid.getPort(cfg.portId);
            if (port == null) continue;
            if (port.getStatus() != Port.Status.CLOSED) continue;

            port.open();
            System.out.printf("[%s] Port %s reopened after break%n",
                    sim.getTimeLabel(), cfg.portId);

            if (grid.hasQueuedShipments()) {
                Shipment next = grid.dequeueShipment();
                if (next != null) {
                    if (port.canHandle(next) && port.hasQueueCapacity()) {
                        port.enqueue(next);
                        Shipment started = port.startNextShipment();
                        if (started != null) {
                            requestFirstBin(sim, port, started);
                        }
                    } else {
                        grid.enqueueShipment(next);
                    }
                }
            }
        }
        java.util.Map<String, Object> logData = new java.util.HashMap<>();
        logData.put("gridId", gridId);
        sim.logEvent("BreakEnd", logData);
    }

    /**
     * Schedules the first {@link BinArrivedAtPort} event for a newly started shipment.
     *
     * <p>Marks the bin as {@link Bin.Status#OUTSIDE} immediately (in transit) and
     * fires the arrival after the grid's configured delivery delay.
     *
     * @param sim      running simulation
     * @param port     port that will receive the bin
     * @param shipment shipment whose first pick is being initiated
     */
    private void requestFirstBin(Simulation sim, Port port, Shipment shipment) {
        RouterDTOs.Pick pick = shipment.nextPick();
        if (pick == null) return;

        Bin bin = sim.getBin(pick.binId);
        if (bin == null) return;

        bin.markOutside();

        double delay = sim.getDeliveryDelay(shipment.getPackingGrid());
        sim.schedule(new BinArrivedAtPort(
                sim.getCurrentTime() + delay,
                sim.nextSequence(),
                port.getId(),
                shipment.getId(),
                pick.binId, pick.ean, pick.qty,
                shipment.getPackingGrid()
        ));
    }
}