import java.io.Serializable;
import java.util.Map;
public class Shipment implements Serializable{

    private final String id;                                              // shipment id 
    public final Map<String, Integer> items;
    public final String shipmentDate;
    
    public enum ShipmentStatus{
        RECEIVED,
        ROUTED,
        CONSOLIDATION,
        READY,
        PICKING,
        PACKED,         // Reikia skaiciuoti Dwell time - laika kol pakeis busena i shipped
        SHIPPED
    }               
    private ShipmentStatus status;
    
    private final long receivedTime; // Simuliacijos sekundes kai gautas uzsakymas
    private long packedTime;         // tikrinam ar "Packed on Time"
    private long shippedTime;        // skaiciuojam "Lead Time"

// Fixed: Public constructor + dynamic time
    public Shipment(String id, Map<String, Integer> items, 
                    String shipmentDate, long simTime) {
        this.id = id;
        this.items = items;
        this.shipmentDate = shipmentDate;
        this.status = ShipmentStatus.RECEIVED; 
        this.receivedTime = simTime; 
    }

    public void markAsPicking(long simTime){
        this.status = ShipmentStatus.PICKING;
    }

    public void markAsPacked(long simTime) {
        this.status = ShipmentStatus.PACKED; 
        this.packedTime = simTime; 
    }

    public void markAsShipped(long simTime) {
        this.status = ShipmentStatus.SHIPPED; 
        this.shippedTime = simTime; 
    }

    // Getters
    public String getId() { return id; }
    public ShipmentStatus getStatus() { return status; }
    public long getReceivedTime() { return receivedTime; }
    public long getPackedTime() { return packedTime; }
    public long getShippedTime() { return shippedTime; }
}
