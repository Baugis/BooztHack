import java.io.Serializable;
import java.util.Map;
public class Shipment implements Serializable{

    String id;                                              // shipment id 
    Map<String, Integer> items;
    String shipmentDate;

    public Shipment(String id, Map<String, Integer> items, String shipmentDate) {
        this.id = id;
        this.items = items;
        this.shipmentDate = shipmentDate;
    }

    
}