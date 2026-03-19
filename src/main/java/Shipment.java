import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public class Shipment implements Serializable{

    private final String id;                                             
    public final Map<String, Integer> items;
    public final String shipmentDate;
    public final String sortingDirection;


    Set<String> handlingFlags = new HashSet<>();  // e.g fragile, heavy ir t.t

    public enum ShipmentStatus{
        RECEIVED,
        ROUTED,
        CONSOLIDATION,
        READY,
        PICKING,
        PACKED,         // Reikia skaiciuoti Dwell time - laika kol pakeis busena i shipped
        SHIPPED
    }      
    
    
    private transient ShipmentStatus status;
    private transient double receivedTime; 
    private transient double packedTime = -1; 
    private transient double shippedTime = -1;


    // 1. The MASTER Constructor kai naudojam visus JSON atributus
    public Shipment(String id, Map<String, Integer> items, String shipmentDate, 
                    double simTime, Set<String> handlingFlags, String sortingDirection) {
        this.id = id;
        this.items = items;
        this.shipmentDate = shipmentDate;
        this.handlingFlags = (handlingFlags != null) ? handlingFlags : new HashSet<>();
        this.sortingDirection = (sortingDirection != null) ? sortingDirection : "NONE";
        this.status = ShipmentStatus.RECEIVED; 
        this.receivedTime = simTime; 
    }

    // 2. Level 1 konstruktorius, kai nenaudojame visu parametru
    public Shipment(String id, Map<String, Integer> items,
                    String shipmentDate, double simTime) {
        this(id, items, shipmentDate, simTime, new HashSet<>(),
        "DEFAULT-DIR");
    }

    public void markAsPicking(long simTime){
        this.status = ShipmentStatus.PICKING;
    }

    public void markAsPacked(long simTime) {
        this.status = ShipmentStatus.PACKED; 
        this.packedTime = simTime; 
    }

    public void markAsShipped(double simTime) {
        this.status = ShipmentStatus.SHIPPED; 
        this.shippedTime = simTime; 
    }

    // Getters
    public String getId() { return id; }
    public ShipmentStatus getStatus() { return status; }
    public double getReceivedTime() { return receivedTime; }
    public double getPackedTime() { return packedTime; }
    public double getShippedTime() { return shippedTime; }
    public Set<String> getHandlingFlags() { return handlingFlags; }
    public String getSortingDirection() { return sortingDirection; }
    
}
