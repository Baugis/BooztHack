
/**
 * EVENT: BinPickCompleted
 *
 * Fires when a port finishes picking the required items from a bin.
 *
 * After this event:
 * - Stock is deducted from the bin
 * - The bin is returned to AVAILABLE
 * - If the shipment has more picks: requests the next bin (schedules BinArrivedAtPort)
 * - If the shipment is fully picked: marks it PACKED, frees the port for the next shipment
 */
public class BinPickCompleted extends Event {

    private final String portId;
    private final String shipmentId;
    private final String binId;
    private final String ean;
    private final int qty;
    private final double pickDuration; // for logging/metrics

    public BinPickCompleted(double simTime, long sequenceNumber,
                            String portId, String shipmentId,
                            String binId, String ean, int qty, double pickDuration) {
        super(simTime, sequenceNumber);
        this.portId       = portId;
        this.shipmentId   = shipmentId;
        this.binId        = binId;
        this.ean          = ean;
        this.qty          = qty;
        this.pickDuration = pickDuration;
    }

    @Override
    public void execute(Simulation sim) {
        System.out.printf("[%.0fs] BinPickCompleted: bin=%s, %d x %s, port=%s, shipment=%s%n",
                sim.getCurrentTime(), binId, qty, ean, portId, shipmentId);

        Shipment shipment = sim.getShipment(shipmentId);
        if (shipment == null) {
            System.err.println("BinPickCompleted: unknown shipment " + shipmentId);
            return;
        }

        Bin bin = sim.getBin(binId);
        if (bin == null) {
            System.err.println("BinPickCompleted: unknown bin " + binId);
            return;
        }

        Grid grid = sim.getGrid(shipment.getPackingGrid());
        Port port = grid != null ? grid.getPort(portId) : null;

        // --- 1. Deduct the picked items from the bin's stock ---
        bin.deductStock(ean, qty);

        // --- 2. Return the bin to the grid ---
        bin.markAvailable();

        // --- 3. Advance the shipment's pick progress ---
        shipment.completeCurrentPick();

        // --- 4. Check if there are more picks to do ---
        if (!shipment.allPicksDone()) {
            // Request the next bin in the pick list
            RouterCaller.Pick nextPick = shipment.nextPick();
            requestNextBin(sim, port, shipment, nextPick);
            return;
        }

        // --- 5. All picks done — shipment is now PACKED ---
        shipment.markPacked(sim.getCurrentTime());
        System.out.printf("[%.0fs] PACKED: shipment=%s (pack duration=%.1fs)%n",
                sim.getCurrentTime(), shipmentId, pickDuration);

        // --- 6. Free the port and start the next shipment if queued ---
        if (port != null) {
            Shipment nextShipment = port.finishCurrentShipment();
            if (nextShipment != null) {
                // Port is already BUSY with the next shipment — request its first bin
                requestNextBin(sim, port, nextShipment, nextShipment.nextPick());
            } else {
                // Port is now IDLE — check if the grid queue has something
                tryAssignFromGridQueue(sim, grid, port);
            }
        }
    }

    /**
     * Schedules a BinArrivedAtPort for the next pick in this shipment.
     * Marks the bin OUTSIDE (it's now en route to the port).
     */
    private void requestNextBin(Simulation sim, Port port, Shipment shipment, RouterCaller.Pick pick) {
        if (pick == null) return;

        Bin bin = sim.getBin(pick.binId);
        if (bin == null) {
            System.err.println("Next pick references unknown bin: " + pick.binId);
            return;
        }

        bin.markOutside();

        double deliveryDelay = sim.getDeliveryDelay(shipment.getPackingGrid());
        double arrivalTime = sim.getCurrentTime() + deliveryDelay;

        sim.schedule(new BinArrivedAtPort(
                arrivalTime,
                sim.nextSequence(),
                portId,
                shipmentId,
                pick.binId,
                pick.ean,
                pick.qty
        ));

        System.out.printf("[%.0fs] Next bin requested: %s (arrives in %.1fs)%n",
                sim.getCurrentTime(), pick.binId, deliveryDelay);
    }

    /**
     * When a port becomes IDLE after finishing a shipment, check if the
     * grid-level queue has anything waiting that this port can handle.
     */
    private void tryAssignFromGridQueue(Simulation sim, Grid grid, Port port) {
        if (grid == null) return;

        // Peek through the grid queue to find the first compatible shipment
        // For Level 1 this is simple — just grab the front of the queue
        Shipment next = grid.dequeueShipment();
        if (next == null) return;

        if (port.canHandle(next)) {
            port.enqueue(next);
            Shipment started = port.startNextShipment();
            if (started != null) {
                requestNextBin(sim, port, started, started.nextPick());
                System.out.printf("[%.0fs] Port %s pulled %s from grid queue%n",
                        sim.getCurrentTime(), portId, started.getId());
            }
        } else {
            // Can't handle it — put it back
            grid.enqueueShipment(next);
        }
    }
}
