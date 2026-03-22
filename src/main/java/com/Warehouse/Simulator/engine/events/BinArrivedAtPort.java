package com.Warehouse.Simulator.engine.events;

import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.model.Bin;
import com.Warehouse.Simulator.model.Grid;
import com.Warehouse.Simulator.model.Shipment;

/**
 * EVENT: BinArrivedAtPort
 *
 * Fires when a bin physically arrives at a port after travelling from the grid.
 * The port can now start picking items from it.
 *
 * <p>Schedules a {@link BinPickCompleted} event after the appropriate pick duration:
 * <ul>
 *   <li><b>Standard shipments:</b> 140 units/hour</li>
 *   <li><b>Fragile shipments:</b> 70 units/hour (half speed, see README → Handling Flags)</li>
 * </ul>
 * Pick duration includes a ±20% random jitter to simulate real-world variability.
 */
public class BinArrivedAtPort extends Event {

    /** Picking speed for standard (non-fragile) shipments, converted to units/second. */
    private static final double STANDARD_UNITS_PER_SECOND = 140.0 / 3600.0;

    /** Picking speed for fragile shipments, converted to units/second. */
    private static final double FRAGILE_UNITS_PER_SECOND = 70.0 / 3600.0;

    /** Lower bound of the random duration multiplier (−20%). */
    private static final double RANDOM_MIN = 0.8;

    /** Upper bound of the random duration multiplier (+20%). */
    private static final double RANDOM_MAX = 1.2;

    /** ID of the port where this bin has arrived. */
    private final String portId;

    /** ID of the shipment this pick operation belongs to. */
    private final String shipmentId;

    /** ID of the bin that has arrived at the port. */
    private final String binId;

    /** EAN of the product to be picked from this bin. */
    private final String ean;

    /** Quantity of {@link #ean} to pick. */
    private final int qty;

    /** ID of the grid the bin originated from. */
    private final String gridId;

    /**
     * Creates a BinArrivedAtPort event.
     *
     * @param simTime        simulation timestamp at which the bin arrives
     * @param sequenceNumber tie-breaker for events scheduled at the same time
     * @param portId         port the bin has arrived at
     * @param shipmentId     shipment this pick is serving
     * @param binId          bin that has physically arrived
     * @param ean            product barcode to pick
     * @param qty            quantity to pick
     * @param gridId         originating grid of the bin
     */
    public BinArrivedAtPort(double simTime, long sequenceNumber,
                            String portId, String shipmentId,
                            String binId, String ean, int qty, String gridId) {
        super(simTime, sequenceNumber);
        this.portId     = portId;
        this.shipmentId = shipmentId;
        this.binId      = binId;
        this.ean        = ean;
        this.qty        = qty;
        this.gridId     = gridId;
    }

    /**
     * Handles the bin's arrival at the port.
     *
     * <p>Execution steps:
     * <ol>
     *   <li>Validates that the shipment and grid/bin still exist in the simulation.</li>
     *   <li>Skips silently if the shipment is already {@code PACKED} or {@code SHIPPED}
     *       (can happen when another bin completed the shipment while this one was in transit).</li>
     *   <li>Attempts to reserve the bin for this port. If the bin is already held by
     *       another port, this port is added to the bin's FCFS waiting queue and no
     *       further action is taken until the bin is released.</li>
     *   <li>Calculates pick duration based on handling flags and a random ±20% jitter.</li>
     *   <li>Schedules a {@link BinPickCompleted} event at the calculated completion time.</li>
     * </ol>
     *
     * @param sim the running {@link Simulation} instance
     */
    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%s] BinArrivedAtPort: bin=%s, port=%s, shipment=%s%n",
                sim.getTimeLabel(), binId, portId, shipmentId);

        Shipment shipment = sim.getShipment(shipmentId);
        if (shipment == null) {
            System.err.println("BinArrivedAtPort: unknown shipment " + shipmentId);
            return;
        }

        if (shipment.getStatus() == Shipment.ShipmentStatus.PACKED ||
            shipment.getStatus() == Shipment.ShipmentStatus.SHIPPED) {
            System.out.printf("[%s] BinArrivedAtPort: skipping — shipment %s already %s%n",
                    sim.getTimeLabel(), shipmentId, shipment.getStatus());
            return;
        }

        Grid grid = sim.getGrid(gridId);
        if (grid == null) {
            System.err.println("BinArrivedAtPort: unknown grid " + gridId);
            return;
        }
        Bin bin = grid.getBin(binId);
        if (bin == null) {
            System.err.println("BinArrivedAtPort: unknown bin " + binId);
            return;
        }

        if (bin.getStatus() == Bin.Status.RESERVED && portId.equals(bin.getReservedByPortId())) {
        } else {
            boolean reserved = bin.reserve(portId);
            if (!reserved) {
                System.out.printf("[%s] Bin %s already reserved, port %s added to waiting list%n",
                        sim.getTimeLabel(), binId, portId);
                return;
            }
        }

        boolean isFragile       = shipment.getHandlingFlags().contains("fragile");
        double  unitsPerSecond  = isFragile ? FRAGILE_UNITS_PER_SECOND : STANDARD_UNITS_PER_SECOND;
        double  baseDuration    = qty / unitsPerSecond;
        double  randomFactor    = RANDOM_MIN + Math.random() * (RANDOM_MAX - RANDOM_MIN);
        double  pickDuration    = baseDuration * randomFactor;
        double  completionTime  = sim.getCurrentTime() + pickDuration;

        sim.schedule(new BinPickCompleted(
                completionTime,
                sim.nextSequence(),
                portId,
                shipmentId,
                binId,
                ean,
                qty,
                pickDuration,
                gridId
        ));

        System.out.printf("[%s] Pick scheduled: %d x %s from bin %s, takes %.1fs%n",
                sim.getTimeLabel(), qty, ean, binId, pickDuration);
    }
}