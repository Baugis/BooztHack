/**
 * EVENT: BinTransferCompleted
 *
 * Fires when an inter-grid bin transfer finishes (spec section 4.4).
 * The bin has physically moved from a source grid to a destination grid.
 *
 * After this event:
 *   - The bin is removed from the source grid and added to the destination grid.
 *   - The bin is marked AVAILABLE.
 *   - The shipment's pending-transfer count is decremented.
 *   - If all transfers for the shipment are done, it transitions
 *     CONSOLIDATION -> READY and is assigned to a port.
 */
public class BinTransferCompleted extends Event {

    private final String binId;
    private final String sourceGridId;
    private final String destinationGridId;
    private final String shipmentId;
    private final double transferDuration;

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

        Grid sourceGrid = sim.getGrid(sourceGridId);
        Grid destGrid   = sim.getGrid(destinationGridId);

        if (sourceGrid == null || destGrid == null) {
            System.err.printf("BinTransferCompleted: unknown grid(s) src=%s dst=%s%n",
                    sourceGridId, destinationGridId);
            return;
        }

        Bin bin = sourceGrid.getBin(binId);
        if (bin == null) {
            System.err.println("BinTransferCompleted: unknown bin " + binId);
            return;
        }

        // Move the bin to its new grid
        sourceGrid.removeBin(binId);
        bin.setGridId(destinationGridId);
        destGrid.addBin(bin);
        bin.markAvailable();

        // Notify the shipment that one of its transfers is complete
        if (shipmentId != null) {
            Shipment shipment = sim.getShipment(shipmentId);
            if (shipment != null) {
                shipment.decrementPendingTransfers();

                if (shipment.allTransfersDone()
                        && shipment.getStatus() == Shipment.ShipmentStatus.CONSOLIDATION) {

                    // All bins have arrived at the destination grid — shipment is READY
                    shipment.markAsReady();
                    System.out.printf("[%.0fs] Shipment %s READY (all transfers done)%n",
                            sim.getCurrentTime(), shipmentId);

                    // Try to assign to a port in the destination grid
                    Port port = destGrid.findBestPortFor(shipment);
                    if (port != null) {
                        port.enqueue(shipment);
                        if (port.getStatus() == Port.Status.IDLE) {
                            Shipment started = port.startNextShipment();
                            if (started != null) {
                                requestFirstBin(sim, port, started);
                            }
                        }
                    } else {
                        destGrid.enqueueShipment(shipment);
                    }
                }
            }
        }
    }

    private void requestFirstBin(Simulation sim, Port port, Shipment shipment) {
        RouterCaller.Pick pick = shipment.nextPick();
        if (pick == null) return;
        Bin bin = sim.getBin(pick.binId);
        if (bin == null) return;
        bin.markOutside();
        double delay = sim.getDeliveryDelay(shipment.getPackingGrid());
        sim.schedule(new BinArrivedAtPort(
                sim.getCurrentTime() + delay,
                sim.nextSequence(),
                port.getPortId(),
                shipment.getId(),
                pick.binId, pick.ean, pick.qty,
                shipment.getPackingGrid()
        ));
    }
}