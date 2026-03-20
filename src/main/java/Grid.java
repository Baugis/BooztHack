import java.util.*;

public class Grid {

    private final String gridId;
    private final Map<String, Bin> bins;
    private final List<Shift> shifts;
    private final Queue<Shipment> gridQueue;

    /** All ports belonging to this grid, keyed by portId. */
    private final Map<String, Port> ports;

    public Grid(String gridID, List<Shift> shifts) {
        this.gridId = gridID;
        this.shifts = new ArrayList<>(shifts);
        this.bins = new HashMap<>();
        this.gridQueue = new LinkedList<>();
        this.ports = new LinkedHashMap<>();
    }

    // -------------------------------------------------------------------------
    // Bin management
    // -------------------------------------------------------------------------

    public void addBin(Bin bin) {
        bins.put(bin.getBinId(), bin);
    }

    public Bin getBin(String binId) {
        return bins.get(binId);
    }

    public Collection<Bin> getAllBins() {
        return Collections.unmodifiableCollection(bins.values());
    }

    /**
     * Removes a bin from this grid (needed after an inter-grid transfer).
     */
    public void removeBin(String binId) {
        bins.remove(binId);
    }

    // -------------------------------------------------------------------------
    // Port management
    // -------------------------------------------------------------------------

    public void addPort(Port port) {
        ports.put(port.getPortId(), port);
    }

    public Port getPort(String portId) {
        return ports.get(portId);
    }

    public Collection<Port> getAllPorts() {
        return Collections.unmodifiableCollection(ports.values());
    }

    /**
     * Selects the best available port for a shipment.
     *
     * "Best" is defined as:
     *   1. Port must be IDLE or BUSY (not CLOSED / PENDING_CLOSE).
     *   2. Port must be compatible with the shipment's handling flags.
     *   3. Port must have queue capacity remaining.
     *   4. Among all eligible ports, pick the one with the shortest current queue.
     *
     * Returns null if no port can accept the shipment right now.
     */
    public Port findBestPortFor(Shipment shipment) {
        Port best = null;
        int bestQueueSize = Integer.MAX_VALUE;

        for (Port port : ports.values()) {
            Port.Status status = port.getStatus();
            if (status == Port.Status.CLOSED || status == Port.Status.PENDING_CLOSE) {
                continue;
            }
            if (!port.isCompatibleWith(shipment)) {
                continue;
            }
            if (!port.hasQueueCapacity()) {
                continue;
            }
            if (port.getQueueSize() < bestQueueSize) {
                bestQueueSize = port.getQueueSize();
                best = port;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Grid shipment queue
    // -------------------------------------------------------------------------

    public void enqueueShipment(Shipment shipment) {
        gridQueue.add(shipment);
    }

    public Shipment dequeueShipment() {
        return gridQueue.poll();
    }

    public boolean hasQueuedShipments() {
        return !gridQueue.isEmpty();
    }

    public Queue<Shipment> getGridQueue() {
        return new LinkedList<>(gridQueue);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getId()            { return gridId; }
    public List<Shift> getShifts()   { return Collections.unmodifiableList(shifts); }
}