/**
 * EVENT: BinTransferStarted
 *
 * Fires immediately when the router decides a bin needs to move from one
 * grid to another (consolidation path in ShipmentRouterTriggered).
 *
 * Responsibilities:
 *   1. Marks the bin as OUTSIDE (in-transit, unavailable for other picks).
 *   2. Logs the transfer start for visibility.
 *   3. Schedules BinTransferCompleted after the conveyor delay.
 *
 * The actual bin relocation (removing from source grid, adding to dest grid)
 * happens inside BinTransferCompleted once the bin physically arrives.
 */
public class BinTransferStarted extends Event {

    private final String binId;
    private final String sourceGridId;
    private final String destinationGridId;
    private final String shipmentId;
    private final double transferDelay;

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

    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%s] BinTransferStarted: bin=%s %s -> %s (eta=%.0fs)%n",
                sim.getTimeLabel(), binId, sourceGridId, destinationGridId, transferDelay);

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

        // Mark bin as in-transit so it can't be picked by another shipment
        bin.markOutside();

        // Schedule arrival at the destination grid
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