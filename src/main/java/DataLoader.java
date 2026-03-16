import java.io.FileReader;
import java.io.IOException;
import com.google.gson.Gson;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

public class DataLoader {
    private final String shipmentPath = "Data/sample-data/level1/shipments.json";
    private final String gridPath = "Data/sample-data/level1/grid.json";
    private final String binPath = "Data/sample-data/level1/bin.json"; 
    private final Gson gson = new Gson();

    public List<Shipment> loadShipmentsJson() {
    try (FileReader fileReader = new FileReader(shipmentPath)) {
        Shipment[] shipmentArray = gson.fromJson(fileReader, Shipment[].class);
        return (shipmentArray != null) ? new ArrayList<>(Arrays.asList(shipmentArray)) : new ArrayList<>();
        } catch (IOException e) {
            System.err.println("Error, did not load shipments.json.");
            return new ArrayList<>();
        }
    }

    public List<Grid> loadGridJson(){
        try ( FileReader fileReader = new FileReader(gridPath)) {
            Grid[] gridArray = gson.fromJson(fileReader, Grid[].class);
            return (gridArray != null) ? new ArrayList<>(Arrays.asList(gridArray)) : new ArrayList<>();

        } catch (IOException e){
            System.err.println("Error, did not load grid.json.");
            return new ArrayList<>();
        }
    }

    public List<Grid> loadBinJson(){
        try ( FileReader fileReader = new FileReader(binPath)) {
            Bin[] binArray = gson.fromJson(fileReader, Bin[].class);
            return (binArray != null) ? new ArrayList<>(Arrays.asList(binArray)) : new ArrayList<>();
        } catch (IOException e){
            System.err.println("Error, did not load bin.json.");
            return new ArrayList<>();
        }
    }
}
