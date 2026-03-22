package com.Warehouse.Simulator.engine.events;

/**
 * EVENT: BinPickCompleted
 *
 * Fires when a port finishes picking the required quantity of a single EAN
 * from a bin. This is the central state-transition event in the picking pipeline:
 * it deducts stock, releases the bin, advances shipment progress, and either
 * kicks off the next pick or marks the shipment as PACKED.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Deduct picked quantity from bin stock.</li>
 *   <li>Release bin; hand it off to the next waiting port (FCFS) if any.</li>
 *   <li>Advance the shipment's pick cursor.</li>
 *   <li>If more picks remain → request the next bin.</li>
 *   <li>If all picks done → mark shipment PACKED, free the port, start next shipment.</li>
 * </ol>
 */
public class BinPickCompleted extends Event {

    /** Port that performed the pick. */
    private final String portId;

    /** Shipment this pick belongs to. */
    private final String shipmentId;

    /** Bin the items were picked from. */
    private final String binId;

    /** EAN (product barcode) that was picked. */
    private final String ean;

    /** Quantity picked. */
    private final int qty;

    /**
     * Actual pick duration in seconds (base + jitter), carried from
     * {@link BinArrivedAtPort} and used for pack-time logging.
     */
    private final double pickDuration;

    /** Grid the bin originated from. */
    private final String gridId;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * @param simTime        simulation time at which the pick completes
     * @param sequenceNumber tie-breaker for same-timestamp events
     * @param portId         port that performed the pick
     * @param shipmentId     shipment being picked
     * @param binId          bin that was picked from
     * @param ean            product barcode picked
     * @param qty            quantity picked
     * @param pickDuration   actual duration of this pick (seconds, including jitter)
     * @param gridId         originating grid of the bin
     */
    public BinPickCompleted(double simTime, long sequenceNumber,
                            String portId, String shipmentId,
                            String binId, String ean, int qty, double pickDuration, String gridId) {
        super(simTime, sequenceNumber);
        this.portId       = portId;
        this.shipmentId   = shipmentId;
        this.binId        = binId;
        this.ean          = ean;
        this.qty          = qty;
        this.pickDuration = pickDuration;
        this.gridId       = gridId;
    }

    // -------------------------------------------------------------------------
    // Event execution
    // -------------------------------------------------------------------------

    /**
     * Processes the completion of a bin pick.
     *
     * <p>Guards first: resolves shipment, grid, bin, and port from the simulation
     * state. Any missing entity is treated as a fatal inconsistency and logged to
     * stderr before returning.
     *
     * @param sim the running {@link Simulation} instance
     */
    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%s] BinPickCompleted: bin=%s, %d x %s, port=%s, shipment=%s%n",
                sim.getTimeLabel(), binId, qty, ean, portId, shipmentId);

        // --- Guards: resolve all required entities ---
        Shipment shipment = sim.getShipment(shipmentId);
        if (shipment == null) {
            System.err.println("BinPickCompleted: unknown shipment " + shipmentId);
            return;
        }

        Grid grid = sim.getGrid(gridId);
        if (grid == null) {
            System.err.println("BinPickCompleted: unknown grid " + gridId);
            return;
        }

        Bin bin = grid.getBin(binId);
        if (bin == null) {
            System.err.println("BinPickCompleted: unknown bin " + binId);
            return;
        }

        // Ports are searched across all grids because a port's physical location
        // may differ from the bin's originating grid.
        Port port = null;
        for (Grid g : sim.getAllGrids()) {
            port = g.getPort(portId);
            if (port != null) break;
        }
        if (port == null) {
            System.err.println("BinPickCompleted: unknown port " + portId);
            return;
        }

        // --- Step 1: Deduct stock ---
        // Remove the picked quantity from the bin's live inventory.
        bin.deductStock(ean, qty);

        // --- Step 2: Release bin and hand off to next waiting port ---
        // Mark the bin available, then check the FCFS queue. If another port
        // was waiting, immediately reserve the bin for it and re-schedule a
        // BinArrivedAtPort so it can start picking without an extra travel delay.
        bin.markAvailable();
        if (bin.hasWaiting()) {
            String nextPortId = bin.pollNextWaiting();
            bin.reserve(nextPortId);

            // Locate the waiting port across all grids
            Port nextPort = null;
            for (Grid g : sim.getAllGrids()) {
                nextPort = g.getPort(nextPortId);
                if (nextPort != null) break;
            }

            if (nextPort != null && nextPort.getActiveShipment() != null) {
                Shipment waiting = nextPort.getActiveShipment();
                RouterDTOs.Pick pick = waiting.nextPick();
                if (pick != null) {
                    // Bin still needs to travel to the waiting port's grid
                    double delay = sim.getDeliveryDelay(waiting.getPackingGrid());
                    sim.schedule(new BinArrivedAtPort(
                            sim.getCurrentTime() + delay,
                            sim.nextSequence(),
                            nextPortId,
                            waiting.getId(),
                            bin.getBinId(),
                            pick.ean,
                            pick.qty,
                            waiting.getPackingGrid()
                    ));
                    System.out.printf("[%s] Bin %s handed to waiting port %s%n",
                            sim.getTimeLabel(), bin.getBinId(), nextPortId);
                }
            }
        }

        // --- Step 3: Advance pick cursor ---
        // Marks the current pick as done so the shipment knows to move to the next one.
        shipment.completeCurrentPick();

        // --- Step 4: More picks remaining → request next bin ---
        if (!shipment.allPicksDone()) {
            RouterDTOs.Pick nextPick = shipment.nextPick();
            requestNextBin(sim, port, shipment, nextPick);
            return;
        }

        // --- Step 5: All picks done → mark shipment PACKED ---
        shipment.markAsPacked(sim.getCurrentTime());
        System.out.printf("[%s] PACKED: shipment=%s (pack duration=%.1fs)%n",
                sim.getTimeLabel(), shipmentId, pickDuration);

        // --- Step 6: Free the port and start the next shipment ---
        // finishCurrentShipment() returns the next queued shipment, if any.
        // If the port queue is empty, fall back to pulling from the grid queue.
        Shipment nextShipment = port.finishCurrentShipment();
        if (nextShipment != null) {
            requestNextBin(sim, port, nextShipment, nextShipment.nextPick());
        } else {
            tryAssignFromGridQueue(sim, grid, port);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Requests the next bin for a shipment by marking it as outside its grid
     * and scheduling a {@link BinArrivedAtPort} event after the delivery delay.
     *
     * <p>Called both when a shipment has more picks remaining (step 4) and when
     * a newly started shipment needs its first bin (step 6).
     *
     * @param sim      running simulation
     * @param port     port that will receive the bin
     * @param shipment shipment the pick belongs to
     * @param nextPick pick descriptor containing binId, EAN, and quantity;
     *                 does nothing if {@code null}
     */
    private void requestNextBin(Simulation sim, Port port, Shipment shipment, RouterDTOs.Pick nextPick) {
        if (nextPick == null) return;

        Bin bin = sim.getBin(nextPick.binId);
        if (bin == null) {
            System.err.println("Next pick references unknown bin: " + nextPick.binId);
            return;
        }

        // Mark the bin as outside so other ports know it is in transit
        bin.markOutside();

        double deliveryDelay = sim.getDeliveryDelay(shipment.getPackingGrid());
        double arrivalTime   = sim.getCurrentTime() + deliveryDelay;

        sim.schedule(new BinArrivedAtPort(
                arrivalTime,
                sim.nextSequence(),
                port.getId(),
                shipment.getId(),
                nextPick.binId,
                nextPick.ean,
                nextPick.qty,
                shipment.getPackingGrid()
        ));

        System.out.printf("[%s] Next bin requested: %s (arrives in %.1fs)%n",
                sim.getTimeLabel(), nextPick.binId, deliveryDelay);
    }

    /**
     * Attempts to pull the next shipment from the grid's waiting queue and
     * assign it to an idle port.
     *
     * <p>If the port cannot handle the dequeued shipment's flags, the shipment
     * is re-enqueued so it isn't lost. This is a best-effort assignment —
     * no retry loop is performed; the port will pick up work again on the next
     * {@link BinPickCompleted} cycle.
     *
     * @param sim  running simulation
     * @param grid grid whose shipment queue is checked; does nothing if {@code null}
     * @param port idle port to assign the shipment to
     */
    private void tryAssignFromGridQueue(Simulation sim, Grid grid, Port port) {
        if (grid == null) return;

        Shipment next = grid.dequeueShipment();
        if (next == null) return;

        if (port.canHandle(next)) {
            port.enqueue(next);
            Shipment started = port.startNextShipment();
            if (started != null) {
                requestNextBin(sim, port, started, started.nextPick());
                System.out.printf("[%s] Port %s pulled %s from grid queue%n",
                        sim.getTimeLabel(), port.getId(), started.getId());
            }
        } else {
            // Port doesn't support this shipment's handling flags — put it back
            grid.enqueueShipment(next);
        }
    }
}