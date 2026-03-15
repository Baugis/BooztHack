import com.google.gson.Gson;



public class Shipment{

    String shipment_id;
    String items;
    int item_count;
    String shipment_date;
    
    Shipment(String shipment_id, String items, int item_count, String shipment_date)
    {
        this.shipment_id = shipment_id;
        this.items = items;
        this.item_count = item_count;
        this.shipment_date = shipment_date;
    }



}

