/**
 * EVENT: BreakEndEvent
 *
 * Fires at the end of a scheduled break window.
 * Re-opens any CLOSED ports from this shift and immediately tries to pull
 * waiting shipments from the grid queue.
 */
public class BreakEndEvent extends Event {

    private final String gridId;
    private final Shift shift;
    private final Shift.BreakWindow breakWindow;

    public BreakEndEvent(double simTime, long sequenceNumber,
                         String gridId, Shift shift, Shift.BreakWindow breakWindow) {
        super(simTime, sequenceNumber);
        this.gridId      = gridId;
        this.shift       = shift;
        this.breakWindow = breakWindow;
    }

    @Override
    public void execute(Simulation sim) {
        Grid grid = sim.getGrid(gridId);
        if (grid == null) {
            System.err.println("BreakEndEvent: unknown grid " + gridId);
            return;
        }

        System.out.printf("[%.0fs] BreakEnd: grid=%s break=%s-%s%n",
                sim.getCurrentTime(), gridId, breakWindow.startAt, breakWindow.endAt);

        for (Shift.PortConfig cfg : shift.portConfig) {
            Port port = grid.getPort(cfg.portId);
            if (port == null) continue;

            // Only re-open ports that are actually CLOSED (PENDING_CLOSE ports
            // are still finishing their last shipment from before the break)
            if (port.getStatus() == Port.Status.CLOSED) {
                port.open();
                System.out.printf("[%.0fs] Port %s reopened after break%n",
                        sim.getCurrentTime(), cfg.portId);

                // Pull the next waiting shipment if any
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
        }
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