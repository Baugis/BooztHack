package com.Warehouse.Simulator.model;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Represents a physical grid section of the warehouse.
 *
 * <p>A grid owns three kinds of resources:
 * <ul>
 *   <li><b>Bins</b> — storage units holding stock; may arrive from or depart
 *       to other grids via inter-grid transfers.</li>
 *   <li><b>Ports</b> — picking stations where bins are brought and items are
 *       picked for shipments.</li>
 *   <li><b>Shifts</b> — time windows that define which ports are active and
 *       when breaks occur (see {@link Shift}).</li>
 * </ul>
 *
 * <p>The grid also maintains a <b>shipment queue</b> — a FIFO holding shipments
 * that are waiting for a port to become available. Events such as
 * {@link BreakEndEvent} and {@link BinPickCompleted} drain this queue whenever
 * a port frees up.
 */
public class Grid {

    /** Unique grid identifier (e.g. {@code "AS1"}). */
    private final String gridId;

    /** All bins currently located on this grid, keyed by bin ID. */
    private final Map<String, Bin> bins;

    /** Shifts defining port availability and break windows for this grid. */
    private final List<Shift> shifts;

    /**
     * FIFO queue of shipments waiting for a free port on this grid.
     * Populated when no port can accept a shipment immediately;
     * drained by port-freed events.
     */
    private final Queue<Shipment> gridQueue;

    /**
     * All ports belonging to this grid, keyed by port ID.
     * {@link LinkedHashMap} preserves insertion order for deterministic iteration.
     */
    private final Map<String, Port> ports;

    /**
     * Creates an empty grid with the given shifts. Bins and ports are added
     * separately after construction via {@link #addBin} and {@link #addPort}.
     *
     * @param gridID unique identifier for this grid
     * @param shifts shift definitions loaded from grids.json
     */
    public Grid(String gridID, List<Shift> shifts) {
        this.gridId    = gridID;
        this.shifts    = new ArrayList<>(shifts);
        this.bins      = new HashMap<>();
        this.gridQueue = new LinkedList<>();
        this.ports     = new LinkedHashMap<>();
    }

    /**
     * Registers a bin as physically present on this grid.
     * Called at simulation startup and when a bin arrives via
     * {@link BinTransferCompleted}.
     *
     * @param bin the bin to add
     */
    public void addBin(Bin bin) {
        bins.put(bin.getBinId(), bin);
    }

    /**
     * @param binId bin identifier to look up
     * @return the bin, or {@code null} if not present on this grid
     */
    public Bin getBin(String binId) {
        return bins.get(binId);
    }

    /**
     * @return unmodifiable view of all bins currently on this grid
     */
    public Collection<Bin> getAllBins() {
        return Collections.unmodifiableCollection(bins.values());
    }

    /**
     * Removes a bin from this grid's registry.
     * Called by {@link BinTransferCompleted} before the bin is added to the
     * destination grid — the bin object itself is not destroyed.
     *
     * @param binId ID of the bin to remove
     */
    public void removeBin(String binId) {
        bins.remove(binId);
    }

    /**
     * Registers a port as belonging to this grid.
     * Called during simulation initialisation after shifts are processed.
     *
     * @param port the port to add
     */
    public void addPort(Port port) {
        ports.put(port.getId(), port);
    }

    /**
     * @param portId port identifier to look up
     * @return the port, or {@code null} if not found on this grid
     */
    public Port getPort(String portId) {
        return ports.get(portId);
    }

    /**
     * @return unmodifiable view of all ports on this grid
     */
    public Collection<Port> getAllPorts() {
        return Collections.unmodifiableCollection(ports.values());
    }

    /**
     * Selects the best available port for a shipment using the following
     * criteria in priority order:
     * <ol>
     *   <li>Port must be {@link Port.Status#IDLE} or {@link Port.Status#BUSY}
     *       (CLOSED and PENDING_CLOSE ports are not accepting new work).</li>
     *   <li>Port must be compatible with the shipment's handling flags.</li>
     *   <li>Port must have remaining queue capacity.</li>
     *   <li>Among all eligible ports, the one with the shortest current queue
     *       is preferred to spread load evenly.</li>
     * </ol>
     *
     * @param shipment shipment to find a port for
     * @return the best eligible port, or {@code null} if none can accept the
     *         shipment right now
     */
    public Port findBestPortFor(Shipment shipment) {
        Port best         = null;
        int  bestQueueSize = Integer.MAX_VALUE;

        for (Port port : ports.values()) {
            Port.Status status = port.getStatus();
            if (status == Port.Status.CLOSED || status == Port.Status.PENDING_CLOSE) continue;
            if (!port.isCompatibleWith(shipment)) continue;
            if (!port.hasQueueCapacity())         continue;

            if (port.getQueueSize() < bestQueueSize) {
                bestQueueSize = port.getQueueSize();
                best          = port;
            }
        }
        return best;
    }

    /**
     * Adds a shipment to the back of the grid's waiting queue.
     * Called when no port is immediately available or when a port returns a
     * shipment it cannot handle (e.g. flag mismatch after break end).
     *
     * @param shipment shipment to enqueue
     */
    public void enqueueShipment(Shipment shipment) {
        gridQueue.add(shipment);
    }

    /**
     * Removes and returns the shipment at the front of the grid queue.
     *
     * @return the next waiting shipment, or {@code null} if the queue is empty
     */
    public Shipment dequeueShipment() {
        return gridQueue.poll();
    }

    /**
     * @return {@code true} if at least one shipment is waiting in the grid queue
     */
    public boolean hasQueuedShipments() {
        return !gridQueue.isEmpty();
    }

    /**
     * Returns a snapshot copy of the grid queue for inspection.
     * Mutations to the returned queue do not affect the live queue.
     *
     * @return copy of the current grid queue in FIFO order
     */
    public Queue<Shipment> getGridQueue() {
        return new LinkedList<>(gridQueue);
    }

    /** @return this grid's unique identifier */
    public String getId() { return gridId; }

    /** @return unmodifiable list of shifts defined for this grid */
    public List<Shift> getShifts() { return Collections.unmodifiableList(shifts); }
}