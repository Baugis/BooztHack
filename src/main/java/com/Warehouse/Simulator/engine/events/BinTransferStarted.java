package com.Warehouse.Simulator.engine.events;

/**
 * EVENT: BinTransferStarted
 *
 * Fires immediately when the router decides a bin needs to move from one
 * grid to another (consolidation path in ShipmentRouterTriggered).
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Validates that the source grid and bin exist.</li>
 *   <li>Marks the bin {@link Bin.Status#OUTSIDE} so it cannot be reserved
 *       for other picks while in transit.</li>
 *   <li>Schedules a {@link BinTransferCompleted} event after the conveyor delay.</li>
 * </ol>
 *
 * <p>The actual bin relocation (removing from source grid, adding to destination
 * grid) happens inside {@link BinTransferCompleted} once the bin physically arrives.
 */
public class BinTransferStarted extends Event {

    /** Bin being sent to another grid. */
    private final String binId;

    /** Grid the bin is departing from. */
    private final String sourceGridId;

    /** Grid the bin is travelling to. */
    private final String destinationGridId;

    /** Shipment that requires this inter-grid consolidation transfer. */
    private final String shipmentId;

    /** Conveyor travel time in seconds; passed through to {@link BinTransferCompleted}. */
    private final double transferDelay;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * @param simTime            simulation time at which the transfer is initiated
     * @param sequenceNumber     tie-breaker for same-timestamp events
     * @param binId              bin to transfer
     * @param sourceGridId       grid the bin is leaving
     * @param destinationGridId  grid the bin should arrive at
     * @param shipmentId         shipment that owns this consolidation transfer
     * @param transferDelay      conveyor travel time in seconds
     */
    public BinTransferStarted(double simTime, long sequenceNumber,
                              String binId,
                              String sourceGridId,
                              String destinationGridId,
                              String shipmentId,
                              double transferDelay) {
        super(simTime, sequenceNumber);
        this.binId             = binId;
        this.sourceGridId      = sourceGridId;
        this.destinationGridId = destinationGridId;
        this.shipmentId        = shipmentId;
        this.transferDelay     = transferDelay;
    }

    // -------------------------------------------------------------------------
    // Event execution
    // -------------------------------------------------------------------------

    /**
     * Initiates the bin transfer: marks the bin as in-transit and schedules
     * its arrival at the destination grid.
     *
     * @param sim the running {@link Simulation} instance
     */
    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%s] BinTransferStarted: bin=%s %s -> %s (eta=%.0fs)%n",
                sim.getTimeLabel(), binId, sourceGridId, destinationGridId, transferDelay);

        // --- Guards: source grid and bin must exist ---
        Grid sourceGrid = sim.getGrid(sourceGridId);
        if (sourceGrid == null) {
            System.err.println("BinTransferStarted: unknown source grid " + sourceGridId);
            return;
        }

        Bin bin = sourceGrid.getBin(binId);
        if (bin == null) {
            System.err.println("BinTransferStarted: unknown bin " + binId);
            return;
        }

        // Mark as OUTSIDE so other shipments cannot reserve this bin while it
        // is travelling on the conveyor. Status returns to AVAILABLE in
        // BinTransferCompleted once the bin reaches the destination grid.
        bin.markOutside();

        // Schedule the arrival; transferDelay is passed through so
        // BinTransferCompleted can include it in its own logging.
        sim.schedule(new BinTransferCompleted(
                sim.getCurrentTime() + transferDelay,
                sim.nextSequence(),
                binId,
                sourceGridId,
                destinationGridId,
                shipmentId,
                transferDelay
        ));
    }
}