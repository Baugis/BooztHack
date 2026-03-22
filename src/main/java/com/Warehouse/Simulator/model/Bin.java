package com.Warehouse.Simulator.model;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import com.google.gson.annotations.SerializedName;

/**
 * Represents a physical storage bin in the warehouse grid.
 *
 * <p>A bin holds stock (EAN → quantity), belongs to a grid, and tracks its
 * operational status. When a bin is already reserved, additional ports can
 * queue for it via a FCFS (First-Come-First-Served) waiting list.
 *
 */
public class Bin {

    /**
     * Lifecycle states of a bin.
     *
     * <ul>
     *   <li>{@code AVAILABLE}  – idle, can be reserved immediately.</li>
     *   <li>{@code RESERVED}   – currently held by a port for picking.</li>
     *   <li>{@code OUTSIDE}    – physically outside its home grid (e.g. in
     *       transit or at a packing station); can still be reserved.</li>
     * </ul>
     */
    public enum Status {
        AVAILABLE,
        RESERVED,
        OUTSIDE
    }

    /** Unique bin identifier, mapped from JSON field {@code "id"}. */
    @SerializedName("id")
    private final String binId;

    /**
     * Grid the bin currently resides in, mapped from JSON field {@code "gridId"}.
     * Can change over time as bins are moved between grids.
     */
    @SerializedName("gridId")
    private String gridId;

    /**
     * Current stock: maps EAN barcode strings to available quantities.
     * Mapped from JSON field {@code "items"}.
     */
    @SerializedName("items")
    private final Map<String, Integer> stock;

    /** Current operational status of this bin. */
    private Status status;

    /** ID of the port that currently holds a reservation on this bin, or {@code null}. */
    private String reservedByPortId;

    /**
     * FCFS waiting queue of port IDs that have requested this bin while it was
     * already reserved. Ports are served in arrival order once the bin is freed.
     */
    private final Queue<String> waitingPorts = new LinkedList<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a new bin in {@link Status#AVAILABLE} state.
     *
     * @param binId   unique identifier for this bin
     * @param gridId  grid the bin initially belongs to
     * @param stock   initial EAN → quantity inventory (copied defensively)
     */
    public Bin(String binId, String gridId, Map<String, Integer> stock) {
        this.binId  = binId;
        this.gridId = gridId;
        this.stock  = new HashMap<>(stock);
        this.status = Status.AVAILABLE;
    }

    /** @return the unique bin identifier */
    public String getBinId() { return binId; }

    /** @return the grid this bin currently resides in */
    public String getGridId() { return gridId; }

    /** @return live stock map (EAN → quantity); mutated by {@link #deductStock} */
    public Map<String, Integer> getStock() { return stock; }

    /** @return current {@link Status} of this bin */
    public Status getStatus() { return status; }

    /** @return the port ID holding the current reservation, or {@code null} if none */
    public String getReservedByPortId() { return reservedByPortId; }

    /** @return {@code true} if at least one port is waiting in the FCFS queue */
    public boolean hasWaiting() { return !waitingPorts.isEmpty(); }

    /**
     * Removes and returns the next port ID from the FCFS waiting queue.
     *
     * @return the oldest waiting port ID, or {@code null} if the queue is empty
     */
    public String pollNextWaiting() { return waitingPorts.poll(); }

    // Stock operations

    /**
     * Returns the available quantity for a given EAN in this bin.
     *
     * @param ean product barcode
     * @return quantity in stock, or {@code 0} if the EAN is not present
     */
    public int getQuantity(String ean) {
        return stock.getOrDefault(ean, 0);
    }

    /**
     * Deducts the specified quantity of an EAN from this bin's stock.
     * Removes the EAN entry entirely when the resulting quantity reaches zero.
     *
     * @param ean product barcode to deduct
     * @param qty amount to deduct (must be ≤ current stock)
     * @throws IllegalStateException if {@code qty} exceeds the available stock
     */
    public void deductStock(String ean, int qty) {
        int current = stock.getOrDefault(ean, 0);
        if (qty > current) {
            throw new IllegalStateException(
                "Not enough stock for EAN " + ean + " in bin " + binId +
                " (requested=" + qty + ", available=" + current + ")"
            );
        }
        if (current - qty == 0) {
            stock.remove(ean);
        } else {
            stock.put(ean, current - qty);
        }
    }

    // Status transitions

    /**
     * Attempts to reserve this bin for the given port.
     *
     * <p>If the bin is {@link Status#AVAILABLE} or {@link Status#OUTSIDE}, the
     * reservation succeeds immediately and the bin transitions to
     * {@link Status#RESERVED}. If it is already {@link Status#RESERVED} by
     * another port, the requesting port is added to the FCFS waiting queue.
     *
     * @param portId ID of the port requesting the reservation
     * @return {@code true}  if the reservation was granted immediately;
     *         {@code false} if the port was queued and must wait
     */
    public boolean reserve(String portId) {
        if (status == Status.AVAILABLE || status == Status.OUTSIDE) {
            this.status            = Status.RESERVED;
            this.reservedByPortId  = portId;
            return true;
        } else {
            waitingPorts.add(portId);
            return false;
        }
    }

    /**
     * Marks this bin as {@link Status#OUTSIDE} — physically outside its home
     * grid. Clears the current reservation so the next waiting port (if any)
     * can claim it.
     */
    public void markOutside() {
        this.status           = Status.OUTSIDE;
        this.reservedByPortId = null;
    }

    /**
     * Marks this bin as {@link Status#AVAILABLE}, clearing any existing
     * reservation. The caller is responsible for polling
     * {@link #pollNextWaiting()} to hand the bin off to the next queued port.
     */
    public void markAvailable() {
        this.status           = Status.AVAILABLE;
        this.reservedByPortId = null;
    }

    /**
     * Updates the grid this bin belongs to. Called when a bin is physically
     * moved to a different grid section of the warehouse.
     *
     * @param gridId the new grid identifier
     */
    public void setGridId(String gridId) { this.gridId = gridId; }

    // Debug

    @Override
    public String toString() {
        return "Bin{id=" + binId +
               ", grid=" + gridId +
               ", status=" + status +
               ", reservedBy=" + reservedByPortId +
               ", waiting=" + waitingPorts.size() +
               ", stock=" + stock + "}";
    }
}