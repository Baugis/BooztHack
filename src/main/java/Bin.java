import java.util.HashMap;
import java.util.Map;

/**
 * Represents a physical bin in the warehouse that holds inventory (stock).
 * A bin is always located in a specific Grid, contains quantities of one or
 * more EANs (product codes), and has a lifecycle status that controls whether
 * it can be reserved or moved.
 */
public class Bin {

    /**
     * The possible states a bin can be in during the simulation.
     */
    public enum Status {
        /** Bin is sitting in its grid and is free to be reserved by any port. */
        AVAILABLE,

        /** Bin has been claimed by a specific port and is awaiting pickup or
         *  currently being picked from. No other port may reserve it. */
        RESERVED,

        /** Bin is physically in transit — either travelling from the grid to a
         *  port on a conveyor, or being transferred between grids. */
        OUTSIDE
    }

    /** Unique identifier for this bin (e.g. "BIN-AS1-1"). */
    private final String binId;

    /** The grid this bin currently belongs to (may change after a transfer). */
    private String gridId;

    /**
     * Current stock levels, keyed by EAN (product barcode).
     * Example: { "EAN-1": 5, "EAN-2": 12 }
     * Entries are removed automatically when their quantity reaches zero.
     */
    private final Map<String, Integer> stock;

    /** Current lifecycle status of the bin. Starts as AVAILABLE. */
    private Status status;

    /**
     * ID of the port that currently holds a reservation on this bin.
     * Only meaningful when status == RESERVED; null otherwise.
     */
    private String reservedByPortId;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a new bin with the given identity, home grid, and initial stock.
     * The bin starts in AVAILABLE status with no reservation.
     *
     * @param binId   unique bin identifier
     * @param gridId  ID of the grid this bin starts in
     * @param stock   initial EAN -> quantity mapping (defensively copied)
     */
    public Bin(String binId, String gridId, Map<String, Integer> stock) {
        this.binId = binId;
        this.gridId = gridId;
        this.stock = new HashMap<>(stock); // defensive copy so caller can't mutate us
        this.status = Status.AVAILABLE;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /** @return the unique bin identifier */
    public String getBinId() { return binId; }

    /** @return the ID of the grid this bin is currently assigned to */
    public String getGridId() { return gridId; }

    /** @return a live view of the current stock map (EAN -> quantity) */
    public Map<String, Integer> getStock() { return stock; }

    /** @return the current status of this bin */
    public Status getStatus() { return status; }

    /**
     * @return the port ID that reserved this bin, or null if not reserved
     */
    public String getReservedByPortId() { return reservedByPortId; }

    // -------------------------------------------------------------------------
    // Stock operations
    // -------------------------------------------------------------------------

    /**
     * Returns how many units of the given EAN are currently stored in this bin.
     *
     * @param ean the product code to look up
     * @return quantity available, or 0 if the EAN is not present
     */
    public int getQuantity(String ean) {
        return stock.getOrDefault(ean, 0);
    }

    /**
     * Deducts the specified quantity of an EAN from this bin's stock.
     * Called during the picking process when items are physically removed.
     * If the remaining quantity reaches zero the EAN entry is removed entirely.
     *
     * @param ean product code to deduct
     * @param qty number of units to remove (must be <= current stock)
     * @throws IllegalStateException if there is not enough stock
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
            stock.remove(ean); // keep the map clean — no zero-quantity entries
        } else {
            stock.put(ean, current - qty);
        }
    }

    // -------------------------------------------------------------------------
    // Status transitions
    // -------------------------------------------------------------------------

    /**
     * Marks this bin as RESERVED for the given port.
     * Only an AVAILABLE bin can be reserved; attempting to reserve a bin that
     * is already RESERVED or OUTSIDE will throw an exception.
     *
     * @param portId the ID of the port claiming this bin
     * @throws IllegalStateException if the bin is not currently AVAILABLE
     */
    public void reserve(String portId) {
        if (status != Status.AVAILABLE) {
            throw new IllegalStateException(
                "Bin " + binId + " cannot be reserved — current status: " + status
            );
        }
        this.status = Status.RESERVED;
        this.reservedByPortId = portId;
    }

    /**
     * Transitions the bin to OUTSIDE status, indicating it is now physically
     * moving (conveyor to a port, or inter-grid transfer).
     * Clears any port reservation since the bin is no longer sitting in the grid.
     */
    public void markOutside() {
        this.status = Status.OUTSIDE;
        this.reservedByPortId = null;
    }

    /**
     * Returns the bin to AVAILABLE status once it has been returned to the grid
     * after picking, or after a transfer has completed.
     * Clears any lingering reservation reference.
     */
    public void markAvailable() {
        this.status = Status.AVAILABLE;
        this.reservedByPortId = null;
    }

    // -------------------------------------------------------------------------
    // Location
    // -------------------------------------------------------------------------

    /**
     * Updates which grid this bin belongs to.
     * Called when a bin transfer between grids completes.
     *
     * @param gridId ID of the bin's new home grid
     */
    public void setGridId(String gridId) { this.gridId = gridId; }

    // -------------------------------------------------------------------------
    // Debug
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "Bin{id=" + binId +
               ", grid=" + gridId +
               ", status=" + status +
               ", reservedBy=" + reservedByPortId +
               ", stock=" + stock + "}";
    }
}