/**
 * EVENT: BreakStartEvent
 *
 * Fires at the start of a scheduled break window.
 * Behaves like a mini shift-close: IDLE ports close immediately,
 * BUSY ports go to PENDING_CLOSE so they finish the current shipment first.
 */
public class BreakStartEvent extends Event {

    private final String gridId;
    private final Shift shift;
    private final Shift.BreakWindow breakWindow;

    public BreakStartEvent(double simTime, long sequenceNumber,
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
            System.err.println("BreakStartEvent: unknown grid " + gridId);
            return;
        }

        System.out.printf("[%s] BreakStart: grid=%s break=%s-%s%n",
                sim.getTimeLabel(), gridId, breakWindow.startAt, breakWindow.endAt);

        for (Shift.PortConfig cfg : shift.portConfig) {
            Port port = grid.getPort(cfg.portId);
            if (port == null) continue;

            Port.Status status = port.getStatus();
            if (status == Port.Status.CLOSED || status == Port.Status.PENDING_CLOSE) {
                continue;
            }

            port.requestClose();

            if (port.getStatus() == Port.Status.CLOSED) {
                System.out.printf("[%s] Port %s paused for break%n",
                        sim.getTimeLabel(), cfg.portId);
                // Drain queue back to grid — will be re-assigned when break ends
                for (Shipment s : port.drainQueue()) {
                    grid.enqueueShipment(s);
                }
            } else {
                System.out.printf("[%s] Port %s -> PENDING_CLOSE for break%n",
                        sim.getTimeLabel(), cfg.portId);
            }
        }
    }
}