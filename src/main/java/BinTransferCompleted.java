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
 *
 * Level 9 — Bin Balancing (one-in-one-out):
 *   After the inbound bin arrives, one AVAILABLE bin from the destination grid
 *   is immediately scheduled for transfer back to the source grid to maintain
 *   grid capacity balance. If no available bin exists in the destination grid,
 *   the return transfer is skipped with a warning.
 */
public class BinTransferCompleted extends Event {

    private final String binId;
    private final String sourceGridId;
    private final String destinationGridId;
    private final String shipmentId;
    private final double transferDuration;

    /**
     * Flag to suppress the return transfer for bins that are themselves
     * already a balancing return (prevents infinite ping-pong).
     */
    private final boolean isReturnTransfer;

    /** Main constructor — used for shipment-driven transfers. */
    public BinTransferCompleted(double simTime, long sequenceNumber,
                                String binId,
                                String sourceGridId, String destinationGridId,
                                String shipmentId, double transferDuration) {
        this(simTime, sequenceNumber, binId, sourceGridId, destinationGridId,
                shipmentId, transferDuration, false);
    }

    /** Full constructor — isReturnTransfer=true suppresses the balancing reply. */
    public BinTransferCompleted(double simTime, long sequenceNumber,
                                String binId,
                                String sourceGridId, String destinationGridId,
                                String shipmentId, double transferDuration,
                                boolean isReturnTransfer) {
        super(simTime, sequenceNumber);
        this.binId             = binId;
        this.sourceGridId      = sourceGridId;
        this.destinationGridId = destinationGridId;
        this.shipmentId        = shipmentId;
        this.transferDuration  = transferDuration;
        this.isReturnTransfer  = isReturnTransfer;
    }

    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%s] BinTransferCompleted: bin=%s, %s -> %s (took %.0fs)%s%n",
                sim.getTimeLabel(), binId, sourceGridId, destinationGridId, transferDuration,
                isReturnTransfer ? " [balance return]" : "");

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

        // --- Move the bin to its new grid ---
        sourceGrid.removeBin(binId);
        bin.setGridId(destinationGridId);
        destGrid.addBin(bin);
        bin.markAvailable();

        // --- Notify the shipment that one of its transfers is complete ---
        if (shipmentId != null) {
            Shipment shipment = sim.getShipment(shipmentId);
            if (shipment != null) {
                shipment.decrementPendingTransfers();

                if (shipment.allTransfersDone()
                        && shipment.getStatus() == Shipment.ShipmentStatus.CONSOLIDATION) {

                    shipment.markAsReady();
                    System.out.printf("[%s] Shipment %s READY (all transfers done)%n",
                            sim.getTimeLabel(), shipmentId);

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

        // --- Level 9: Bin Balancing — send one bin back (one-in-one-out) ---
        // Only trigger for inbound shipment transfers, not for the return itself.
        if (!isReturnTransfer) {
            scheduleReturnTransfer(sim, destGrid);
        }
    }

    /**
     * Finds an AVAILABLE bin in the destination grid (excluding the bin that
     * just arrived) and schedules it for transfer back to the source grid.
     *
     * Skips silently if no suitable bin exists.
     */
    private void scheduleReturnTransfer(Simulation sim, Grid destGrid) {
        Bin returnBin = null;
        for (Bin candidate : destGrid.getAllBins()) {
            if (candidate.getBinId().equals(binId)) continue; // skip the one that just arrived
            if (candidate.getStatus() == Bin.Status.AVAILABLE) {
                returnBin = candidate;
                break;
            }
        }

        if (returnBin == null) {
            System.out.printf("[%s] BinBalance: no available bin in %s to return to %s — skipping%n",
                    sim.getTimeLabel(), destinationGridId, sourceGridId);
            return;
        }

        double returnDelay = sim.getTransferDelay(destinationGridId, sourceGridId);

        System.out.printf("[%s] BinBalance: scheduling return bin=%s %s -> %s (delay=%.0fs)%n",
                sim.getTimeLabel(), returnBin.getBinId(),
                destinationGridId, sourceGridId, returnDelay);

        // Mark as outside so it can't be picked while in transit
        returnBin.markOutside();

        // Schedule arrival at the source grid.
        // isReturnTransfer=true prevents an infinite chain of returns.
        sim.schedule(new BinTransferCompleted(
                sim.getCurrentTime() + returnDelay,
                sim.nextSequence(),
                returnBin.getBinId(),
                destinationGridId,   // now travelling back
                sourceGridId,
                null,                // no shipment associated with a balancing return
                returnDelay,
                true                 // this IS a return transfer — do not trigger another return
        ));
    }

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