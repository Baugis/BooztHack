import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import com.google.gson.annotations.SerializedName;

public class Bin {

    public enum Status {
        AVAILABLE,
        RESERVED,
        OUTSIDE
    }

    @SerializedName("id")
    private final String binId;

    @SerializedName("gridId")
    private String gridId;

    @SerializedName("items")
    private final Map<String, Integer> stock;

    private Status status;
    private String reservedByPortId;

    // FCFS waiting list — portai laukiantys šio bino
    private final Queue<String> waitingPorts = new LinkedList<>();

    public Bin(String binId, String gridId, Map<String, Integer> stock) {
        this.binId = binId;
        this.gridId = gridId;
        this.stock = new HashMap<>(stock);
        this.status = Status.AVAILABLE;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public String getBinId() { return binId; }
    public String getGridId() { return gridId; }
    public Map<String, Integer> getStock() { return stock; }
    public Status getStatus() { return status; }
    public String getReservedByPortId() { return reservedByPortId; }

    public boolean hasWaiting() { return !waitingPorts.isEmpty(); }
    public String pollNextWaiting() { return waitingPorts.poll(); }

    // -------------------------------------------------------------------------
    // Stock operations
    // -------------------------------------------------------------------------

    public int getQuantity(String ean) {
        return stock.getOrDefault(ean, 0);
    }

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

    // -------------------------------------------------------------------------
    // Status transitions
    // -------------------------------------------------------------------------

    /**
     * Rezervuoja biną portui. Jei binas užimtas — įdeda portą į laukimo eilę.
     * Grąžina true jei rezervacija pavyko iš karto, false jei reikia laukti.
     */
    public boolean reserve(String portId) {
        if (status == Status.AVAILABLE || status == Status.OUTSIDE) {
            this.status = Status.RESERVED;
            this.reservedByPortId = portId;
            return true;
        } else {
            waitingPorts.add(portId);
            return false;
        }
    }

    public void markOutside() {
        this.status = Status.OUTSIDE;
        this.reservedByPortId = null;
    }

    public void markAvailable() {
        this.status = Status.AVAILABLE;
        this.reservedByPortId = null;
    }

    public void setGridId(String gridId) { this.gridId = gridId; }

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