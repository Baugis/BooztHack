import java.io.FileReader;
import java.io.IOException;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

public class DataLoader {
    private final String shipmentPath = "Data/sample-data/level1/shipments.json";
    private final String gridPath = "Data/sample-data/level1/grids.json";
    private final String binPath = "Data/sample-data/level1/bins.json"; 
    private final Gson gson = new Gson();

// --- 1. Load Shipments (Using DTO) ---
private static class ShipmentLoadDto {
        String id;
        Map<String, Integer> items;
        
        @SerializedName("created_at") // Ensure this matches your JSON!
        String shipmentDate;
        
        @SerializedName("handling_flags")
        Set<String> handlingFlags;
        
        @SerializedName("sorting_direction")
        String sortingDirection;
    }

public List<Shipment> loadShipmentsJson() {
        try (FileReader fileReader = new FileReader(shipmentPath)) {
            ShipmentLoadDto[] shipmentArray = gson.fromJson(fileReader, ShipmentLoadDto[].class);
            List<Shipment> shipments = new ArrayList<>();
            if (shipmentArray != null) {
                for (ShipmentLoadDto dto : shipmentArray) {
                    // Call the MASTER constructor with all the new data!
                    shipments.add(new Shipment(
                        dto.id, 
                        dto.items, 
                        dto.shipmentDate, 
                        0.0, // simTime starts at 0.0
                        dto.handlingFlags, 
                        dto.sortingDirection
                    ));
                }
            }
            return shipments;
        } catch (IOException e) {
            System.err.println("Error, did not load shipments.json.");
            return new ArrayList<>();
        }
    }

    // --- 2. Load Grids (Using DTO) ---
    private static class GridLoadDto {
        String id;
        List<Shift> shifts;
    }

    public List<Grid> loadGridsJson(){
        try (FileReader fileReader = new FileReader(gridPath)) {
            GridLoadDto[] gridArray = gson.fromJson(fileReader, GridLoadDto[].class);
            List<Grid> grids = new ArrayList<>();
            if (gridArray != null) {
                for (GridLoadDto dto : gridArray) {
                    // This calls your REAL constructor so queues/maps aren't null!
                    grids.add(new Grid(dto.id, dto.shifts != null ? dto.shifts : new ArrayList<>()));
                }
            }
            return grids;
        } catch (IOException e){
            System.err.println("Error, did not load grids.json.");
            return new ArrayList<>();
        }
    }

    // --- 3. Load Bins (Flattening the nested quantity) ---
    private static class BinLoadDto {
        String id;
        String currentGridLocation;
        Map<String, QuantityDto> itemsInBin;
    }
    
    private static class QuantityDto {
        int quantity;
    }

    public List<Bin> loadBinsJson(){
        try (FileReader fileReader = new FileReader(binPath)) {
            BinLoadDto[] binArray = gson.fromJson(fileReader, BinLoadDto[].class);
            List<Bin> bins = new ArrayList<>();
            
            if (binArray != null) {
                for (BinLoadDto dto : binArray) {
                    // Flatten the {"EAN": {"quantity": 2}} into {"EAN": 2}
                    Map<String, Integer> flatStock = new HashMap<>();
                    if (dto.itemsInBin != null) {
                        dto.itemsInBin.forEach((ean, qDto) -> flatStock.put(ean, qDto.quantity));
                    }
                    // Call your REAL constructor!
                    bins.add(new Bin(dto.id, dto.currentGridLocation, flatStock));
                }
            }
            return bins;
        } catch (IOException e){
            System.err.println("Error, did not load bins.json.");
            return new ArrayList<>();
        }
    }
}
