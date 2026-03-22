package com.Warehouse.Simulator.engine.events;

import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.model.Bin;
import com.Warehouse.Simulator.model.Grid;
import com.Warehouse.Simulator.model.Port;
import com.Warehouse.Simulator.model.Shipment;
import com.Warehouse.Simulator.router.RouterDTOs;
/**
 * EVENT: BinTransferCompleted
 *
 * Fires when an inter-grid bin transfer finishes (spec section 4.4).
 * The bin has physically moved from a source grid to a destination grid.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Move the bin from source grid to destination grid and mark it AVAILABLE.</li>
 *   <li>Decrement the shipment's pending-transfer counter (if this is a
 *       shipment-driven transfer).</li>
 *   <li>If all transfers are done and the shipment is in CONSOLIDATION state,
 *       transition it to READY and assign it to a port.</li>
 *   <li><b>Bin Balancing (Level 9):</b> schedule one AVAILABLE bin from the
 *       destination grid back to the source grid to maintain capacity balance
 *       (one-in, one-out). Skipped for return transfers to prevent infinite
 *       ping-pong.</li>
 * </ol>
 */
public class BinTransferCompleted extends Event {

    /** Bin being transferred. */
    private final String binId;

    /** Grid the bin is leaving. */
    private final String sourceGridId;

    /** Grid the bin is arriving at. */
    private final String destinationGridId;

    /**
     * Shipment that triggered this transfer, or {@code null} for
     * balancing return transfers that are not tied to any shipment.
     */
    private final String shipmentId;

    /** Actual transfer duration in seconds, used for logging. */
    private final double transferDuration;

    /**
     * When {@code true}, suppresses the Level 9 balancing return transfer.
     * Set on bins that are themselves already a balancing return, preventing
     * an infinite chain of back-and-forth transfers.
     */
    private final boolean isReturnTransfer;

    /**
     * Convenience constructor for shipment-driven transfers.
     * Sets {@code isReturnTransfer = false}, so bin balancing will trigger.
     *
     * @param simTime            simulation time at which the transfer completes
     * @param sequenceNumber     tie-breaker for same-timestamp events
     * @param binId              bin being transferred
     * @param sourceGridId       grid the bin is leaving
     * @param destinationGridId  grid the bin is arriving at
     * @param shipmentId         shipment that owns this transfer
     * @param transferDuration   duration of the transfer in seconds
     */
    public BinTransferCompleted(double simTime, long sequenceNumber,
                                String binId,
                                String sourceGridId, String destinationGridId,
                                String shipmentId, double transferDuration) {
        this(simTime, sequenceNumber, binId, sourceGridId, destinationGridId,
                shipmentId, transferDuration, false);
    }

    /**
     * Full constructor. Pass {@code isReturnTransfer = true} for balancing
     * returns to suppress the reciprocal transfer.
     *
     * @param simTime            simulation time at which the transfer completes
     * @param sequenceNumber     tie-breaker for same-timestamp events
     * @param binId              bin being transferred
     * @param sourceGridId       grid the bin is leaving
     * @param destinationGridId  grid the bin is arriving at
     * @param shipmentId         owning shipment, or {@code null} for balance returns
     * @param transferDuration   duration of the transfer in seconds
     * @param isReturnTransfer   {@code true} to suppress the balancing reply
     */
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

    /**
     * Processes the arrival of a transferred bin at its destination grid.
     *
     * @param sim the running {@link Simulation} instance
     */
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

        sourceGrid.removeBin(binId);
        bin.setGridId(destinationGridId);
        destGrid.addBin(bin);
        bin.markAvailable();

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

        if (!isReturnTransfer) {
            scheduleReturnTransfer(sim, destGrid);
        }
    }


    /**
     * Selects an AVAILABLE bin from the destination grid (excluding the bin that
     * just arrived) and schedules it for transfer back to the source grid.
     *
     * <p>The chosen bin is marked {@link Bin.Status#OUTSIDE} immediately so it
     * cannot be reserved for picks while in transit. The scheduled
     * {@link BinTransferCompleted} is created with {@code isReturnTransfer = true}
     * to prevent an infinite chain.
     *
     * <p>Does nothing if no suitable bin is found; logs a warning instead.
     *
     * @param sim      running simulation
     * @param destGrid the grid that just received an inbound bin
     */
    private void scheduleReturnTransfer(Simulation sim, Grid destGrid) {
        Bin returnBin = null;
        for (Bin candidate : destGrid.getAllBins()) {
            if (candidate.getBinId().equals(binId)) continue;
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

        returnBin.markOutside();

        sim.schedule(new BinTransferCompleted(
                sim.getCurrentTime() + returnDelay,
                sim.nextSequence(),
                returnBin.getBinId(),
                destinationGridId,
                sourceGridId,
                null,
                returnDelay,
                true
        ));
    }

    /**
     * Schedules the first {@link BinArrivedAtPort} event for a newly started shipment.
     *
     * <p>Marks the bin as {@link Bin.Status#OUTSIDE} immediately (in transit)
     * and fires the arrival after the grid's delivery delay.
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