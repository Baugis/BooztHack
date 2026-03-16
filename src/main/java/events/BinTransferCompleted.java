package events;

import events.Event;
import events.Simulation;

/**
 * EVENT: BinTransferCompleted
 *
 * Fires when an inter-grid bin transfer finishes (spec section 4.4).
 * The bin has physically moved from a source grid to a destination grid.
 *
 * After this event:
 * - The bin's gridId is updated to the destination
 * - The bin is marked AVAILABLE again
 * - If the bin's arrival completes all needed transfers for a shipment,
 *   that shipment transitions from CONSOLIDATION -> READY and can be
 *   assigned to a port.
 *
 * NOTE: This event is NOT needed for Level 1-7 (single grid or no transfers).
 * It's included here for completeness and will be fully activated at Level 8.
 * For now it just moves the bin and logs the event.
 */
public class BinTransferCompleted extends Event {

    private final String binId;
    private final String sourceGridId;
    private final String destinationGridId;
    private final String shipmentId; // which shipment triggered this transfer (may be null)
    private final double transferDuration; // for logging

    public BinTransferCompleted(double simTime, long sequenceNumber,
                                String binId,
                                String sourceGridId, String destinationGridId,
                                String shipmentId, double transferDuration) {
        super(simTime, sequenceNumber);
        this.binId             = binId;
        this.sourceGridId      = sourceGridId;
        this.destinationGridId = destinationGridId;
        this.shipmentId        = shipmentId;
        this.transferDuration  = transferDuration;
    }

    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%.0fs] BinTransferCompleted: bin=%s, %s -> %s (took %.0fs)%n",
                sim.getCurrentTime(), binId, sourceGridId, destinationGridId, transferDuration);

        Bin bin = sim.getBin(binId);
        if (bin == null) {
            System.err.println("BinTransferCompleted: unknown bin " + binId);
            return;
        }

        // Move the bin to its new grid
        Grid sourceGrid = sim.getGrid(sourceGridId);
        Grid destGrid   = sim.getGrid(destinationGridId);

        if (sourceGrid != null) {
            // Note: Grid doesn't expose removeBin() yet — add it when Level 8 is built
            // sourceGrid.removeBin(binId);
        }

        if (destGrid != null) {
            bin.setGridId(destinationGridId);
            destGrid.addBin(bin);
            bin.markAvailable();
        }

        // TODO (Level 8): check if this bin's arrival completes all transfers
        // for the associated shipment. If so, transition shipment CONSOLIDATION -> READY
        // and try to assign it to a port.
    }
}