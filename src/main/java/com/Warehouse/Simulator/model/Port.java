package com.Warehouse.Simulator.model;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * MODEL: Port
 *
 * Represents a packing station where items are picked from bins and packed
 * into shipments. Each port belongs to exactly one grid and operates within
 * defined shift windows.
 *
 * A port maintains its own bounded shipment queue (max {@link #MAX_QUEUE_CAPACITY})
 * and processes shipments one at a time. It only accepts shipments whose
 * handling flag requirements are a subset of the port's own supported flags.
 *
 * Lifecycle:
 *   Ports start CLOSED and are opened by ShiftOpenEvent at shift start.
 *   They close (or begin closing) at shift end or break start via ShiftCloseEvent
 *   and BreakStartEvent respectively.
 *
 * Status transitions:
 *   CLOSED -> IDLE        : open() called at shift/break start
 *   IDLE   -> BUSY        : startProcessing() when a shipment is assigned
 *   BUSY   -> IDLE        : finishProcessing() after shipment is packed
 *   BUSY   -> PENDING_CLOSE : requestClose() while a shipment is in progress
 *   PENDING_CLOSE -> CLOSED : finishProcessing() after the in-progress shipment completes
 *   IDLE   -> CLOSED      : requestClose() when no shipment is active
 */
public class Port {

    // Constants
    /** Maximum number of shipments allowed in this port's local queue at any time. */
    public static final int MAX_QUEUE_CAPACITY = 20;

    // Status enum
    /**
     * All lifecycle states a port can occupy during the simulation.
     */
    public enum Status {

        /** Outside its shift window — not accepting or processing any work. */
        CLOSED,

        /**
         * Inside its shift window with no active shipment.
         * Ready to accept the next shipment from its own queue or the grid queue.
         */
        IDLE,

        /** Actively picking items from bins for the current shipment. */
        BUSY,

        /**
         * The shift (or break) ended while the port was BUSY.
         * The port will finish the current shipment and then transition to CLOSED.
         * No new shipments are accepted in this state.
         */
        PENDING_CLOSE
    }

    // Identity & configuration
    /** Unique port identifier, e.g. "port-AS1-0". */
    private final String portId;

    /** ID of the grid this port belongs to. */
    private final String gridId;

    /**
     * Handling flags this port supports, e.g. {"fragile", "heavy"}.
     * A shipment can only be assigned here if all of its required flags
     * are present in this set. Stored as an immutable defensive copy.
     */
    private final Set<String> handlingFlags;

    /** Current lifecycle status. Ports start CLOSED until their shift opens. */
    private Status status;

    /**
     * Ordered queue of shipments assigned to this port and waiting to be picked.
     * Capacity is capped at {@link #MAX_QUEUE_CAPACITY}.
     */
    private final Queue<Shipment> shipmentQueue;

    /**
     * The shipment this port is currently picking and packing.
     * Null when the port is IDLE or CLOSED.
     */
    private Shipment activeShipment;

    /**
     * Creates a new Port in CLOSED status with an empty shipment queue.
     *
     * @param portId        unique port identifier
     * @param gridId        ID of the grid this port belongs to
     * @param handlingFlags set of handling flags this port can process;
     *                      copied defensively so external changes do not affect the port
     */
    public Port(String portId, String gridId, Set<String> handlingFlags) {
        this.portId         = portId;
        this.gridId         = gridId;
        this.handlingFlags  = Set.copyOf(handlingFlags); // immutable defensive copy
        this.status         = Status.CLOSED;
        this.shipmentQueue  = new ArrayDeque<>();
        this.activeShipment = null;
    }

    /** Returns the unique port identifier. */
    public String getId() { return portId; }

    /** Returns the ID of the grid this port belongs to. */
    public String getGridId() { return gridId; }

    /** Returns an unmodifiable view of the handling flags this port supports. */
    public Set<String> getHandlingFlags() { return handlingFlags; }

    /** Returns the current lifecycle status of this port. */
    public Status getStatus() { return status; }

    /** Returns the shipment currently being processed, or null if the port is IDLE or CLOSED. */
    public Shipment getActiveShipment() { return activeShipment; }

    /** Returns the number of shipments currently waiting in this port's queue. */
    public int getQueueSize() { return shipmentQueue.size(); }

    /**
     * Returns true if the port's queue has not yet reached {@link #MAX_QUEUE_CAPACITY}
     * and can accept at least one more shipment.
     */
    public boolean hasQueueCapacity() {
        return shipmentQueue.size() < MAX_QUEUE_CAPACITY;
    }

    /**
     * Returns an unmodifiable snapshot of the current queue order.
     * Intended for inspection and logging only — do not modify the returned list.
     *
     * @return read-only list of queued shipments, front of queue first
     */
    public List<Shipment> getQueueSnapshot() {
        return Collections.unmodifiableList(new LinkedList<>(shipmentQueue));
    }

    /**
     * Returns true if this port can process the given shipment.
     * Compatibility requires that the port's handling flags are a superset of
     * the shipment's required handling flags.
     *
     * @param shipment the shipment to test
     * @return true if this port supports all of the shipment's handling flags
     */
    public boolean isCompatibleWith(Shipment shipment) {
        return handlingFlags.containsAll(shipment.getHandlingFlags());
    }

    /**
     * Adds a shipment to the back of this port's queue.
     *
     * @param shipment the shipment to enqueue
     * @throws IllegalStateException if the port is CLOSED or PENDING_CLOSE (not accepting work),
     *                               or if the queue is already at {@link #MAX_QUEUE_CAPACITY}
     */
    public void enqueue(Shipment shipment) {
        if (status == Status.CLOSED || status == Status.PENDING_CLOSE) {
            throw new IllegalStateException(
                    "Port " + portId + " is not accepting new shipments — status: " + status
            );
        }
        if (!hasQueueCapacity()) {
            throw new IllegalStateException(
                    "Port " + portId + " queue is full (" + MAX_QUEUE_CAPACITY + " shipments)"
            );
        }
        shipmentQueue.add(shipment);
    }

    /**
     * Removes and returns the shipment at the front of the queue.
     * Returns null if the queue is empty.
     *
     * @return the next queued shipment, or null if none
     */
    public Shipment dequeue() {
        return shipmentQueue.poll();
    }

    /**
     * Removes all shipments from this port's queue and returns them as a list,
     * preserving original queue order. Used when a port closes and must return
     * any unstarted work to the grid queue so it is not lost.
     *
     * @return list of drained shipments in original queue order
     */
    public List<Shipment> drainQueue() {
        List<Shipment> drained = new LinkedList<>(shipmentQueue);
        shipmentQueue.clear();
        return drained;
    }

    /**
     * Sets the given shipment as the one currently being processed and
     * transitions the port from IDLE to BUSY.
     *
     * @param shipment the shipment to start processing (must not be null)
     * @throws IllegalStateException if the port is not currently IDLE
     */
    public void startProcessing(Shipment shipment) {
        if (status != Status.IDLE) {
            throw new IllegalStateException(
                    "Port " + portId + " cannot start processing — current status: " + status
            );
        }
        this.activeShipment = shipment;
        this.status = Status.BUSY;
    }

    /**
     * Clears the active shipment after picking and packing are complete.
     * Transitions BUSY -> IDLE so the port can accept the next shipment,
     * or PENDING_CLOSE -> CLOSED if the shift ended while the port was busy.
     *
     * @throws IllegalStateException if there is no active shipment to finish
     */
    public void finishProcessing() {
        if (activeShipment == null) {
            throw new IllegalStateException(
                    "Port " + portId + " has no active shipment to finish"
            );
        }
        this.activeShipment = null;
        if (status == Status.PENDING_CLOSE) {
            this.status = Status.CLOSED;
        } else {
            this.status = Status.IDLE;
        }
    }

    /**
     * Opens the port at shift or break end, transitioning CLOSED -> IDLE.
     *
     * @throws IllegalStateException if the port is not currently CLOSED
     */
    public void open() {
        if (status != Status.CLOSED) {
            throw new IllegalStateException(
                    "Port " + portId + " cannot be opened — current status: " + status
            );
        }
        this.status = Status.IDLE;
    }

    /**
     * Signals that the current shift or break has ended.
     * If the port is IDLE it closes immediately (CLOSED).
     * If the port is BUSY it enters PENDING_CLOSE and will close after the
     * current shipment finishes.
     *
     * @throws IllegalStateException if the port is already CLOSED or PENDING_CLOSE
     */
    public void requestClose() {
        if (status == Status.CLOSED || status == Status.PENDING_CLOSE) {
            throw new IllegalStateException(
                    "Port " + portId + " is already closing or closed — status: " + status
            );
        }
        if (status == Status.BUSY) {
            this.status = Status.PENDING_CLOSE;
        } else { // IDLE
            this.status = Status.CLOSED;
        }
    }

    /**
     * Forces the port to CLOSED status regardless of its current state.
     * Clears the active shipment reference but does NOT drain the queue —
     * the caller is responsible for handling any queued shipments if needed.
     * Intended for emergency shutdown or end-of-simulation cleanup only.
     */
    public void forceClose() {
        this.status = Status.CLOSED;
        this.activeShipment = null;
    }

    /**
     * Alias for {@link #isCompatibleWith(Shipment)}.
     * Provided for readability at call sites in event classes.
     *
     * @param shipment the shipment to check
     * @return true if this port supports all of the shipment's handling flags
     */
    public boolean canHandle(Shipment shipment) {
        return isCompatibleWith(shipment);
    }

    /**
     * Dequeues the next shipment from this port's queue and immediately starts
     * processing it. The port must be IDLE when this is called.
     *
     * @return the shipment that was started, or null if the queue was empty
     */
    public Shipment startNextShipment() {
        Shipment next = dequeue();
        if (next == null) return null;
        startProcessing(next);
        return next;
    }

    /**
     * Finishes the current shipment and immediately starts the next one from
     * the port's queue if one is available.
     *
     * Returns the newly started shipment so the caller can request its first bin,
     * or null if the queue was empty (port is now IDLE) or the port transitioned
     * to CLOSED (was PENDING_CLOSE).
     *
     * @return the next shipment that was started, or null
     */
    public Shipment finishCurrentShipment() {
        finishProcessing(); // clears activeShipment, transitions to IDLE or CLOSED
        if (status == Status.IDLE) {
            return startNextShipment(); // returns null if queue is empty
        }
        return null; // port transitioned to CLOSED — no further work accepted
    }

    /**
     * Returns a concise human-readable description of this port's current state,
     * useful for console logging and debugging.
     */
    @Override
    public String toString() {
        return "Port{id=" + portId +
                ", grid=" + gridId +
                ", status=" + status +
                ", queueSize=" + shipmentQueue.size() +
                ", active=" + (activeShipment != null ? activeShipment.getId() : "none") +
                ", flags=" + handlingFlags + "}";
    }
}