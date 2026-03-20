import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

public class DataLoader {
    private final String shipmentPath = "Data/sample-data/level7/shipments.json";
    private final String gridPath = "Data/sample-data/level7/grids.json";
    private final String binPath = "Data/sample-data/level7/bins.json"; 
    private final Gson gson = new Gson();

    // --- 1. Load Shipments ---
    // Matches actual JSON: "shipmentDate", no handling_flags, no sorting_direction
    private static class ShipmentLoadDto {
        String id;
        Map<String, Integer> items;

        @SerializedName("created_at")
        String createdAt;

        // fallback field name used in actual JSON
        @SerializedName("shipmentDate")
        String shipmentDate;

        @SerializedName("handlingFlags")
        Set<String> handlingFlags;

        @SerializedName("sortingDirection")
        String sortingDirection;
    }

    private final String paramsPath = "Data/sample-data/level7/params.json";

    private static class ParamsDto {
        @SerializedName("truckArrivalSchedules")
        TruckSchedulesDto truckArrivalSchedules;
    }

    private static class TruckSchedulesDto {
        List<TruckScheduleDto> schedules;
    }

    public static class TruckScheduleDto {
        @SerializedName("sortingDirection")
        public String sortingDirection;
        @SerializedName("pullTimes")
        public List<String> pullTimes;
        @SerializedName("weekdays")
        public List<String> weekdays;
    }

    public List<TruckScheduleDto> loadTruckSchedules() {
        try (FileReader fr = new FileReader(paramsPath)) {
            ParamsDto params = gson.fromJson(fr, ParamsDto.class);
            if (params != null && params.truckArrivalSchedules != null) {
                return params.truckArrivalSchedules.schedules;
            }
        } catch (IOException e) {
            System.err.println("Could not load params.json: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    public List<Shipment> loadShipmentsJson() {
        try (FileReader fileReader = new FileReader(shipmentPath)) {
            ShipmentLoadDto[] shipmentArray = gson.fromJson(fileReader, ShipmentLoadDto[].class);
            List<Shipment> shipments = new ArrayList<>();
            if (shipmentArray != null) {
                for (ShipmentLoadDto dto : shipmentArray) {
                    // Use created_at if present, otherwise fall back to shipmentDate
                    String date = dto.createdAt != null ? dto.createdAt : dto.shipmentDate;
                    if (date == null) {
                        System.err.println("Warning: shipment " + dto.id + " has no date — skipping");
                        continue;
                    }
                    Set<String> flags = dto.handlingFlags != null ? dto.handlingFlags : new HashSet<>();
                    String dir = dto.sortingDirection != null ? dto.sortingDirection : "DEFAULT-DIR";

                    shipments.add(new Shipment(
                        dto.id,
                        dto.items,
                        date,
                        0.0,
                        flags,
                        dir
                    ));
                }
            }
            return shipments;
        } catch (IOException e) {
            System.err.println("Error, did not load shipments.json: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // --- 2. Load Grids ---
    private static class GridLoadDto {
        String id;
        List<Shift> shifts;
    }

    public List<Grid> loadGridsJson() {
        try (FileReader fileReader = new FileReader(gridPath)) {
            GridLoadDto[] gridArray = gson.fromJson(fileReader, GridLoadDto[].class);
            List<Grid> grids = new ArrayList<>();
            if (gridArray != null) {
                for (GridLoadDto dto : gridArray) {
                    grids.add(new Grid(dto.id, dto.shifts != null ? dto.shifts : new ArrayList<>()));
                }
            }
            return grids;
        } catch (IOException e) {
            System.err.println("Error, did not load grids.json: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // --- 3. Load Bins ---
    private static class BinLoadDto {
        String id;
        String currentGridLocation;
        Map<String, QuantityDto> itemsInBin;
    }

    private static class QuantityDto {
        int quantity;
    }

    public List<Bin> loadBinsJson() {
        try (FileReader fileReader = new FileReader(binPath)) {
            BinLoadDto[] binArray = gson.fromJson(fileReader, BinLoadDto[].class);
            List<Bin> bins = new ArrayList<>();

            if (binArray != null) {
                for (BinLoadDto dto : binArray) {
                    Map<String, Integer> flatStock = new HashMap<>();
                    if (dto.itemsInBin != null) {
                        dto.itemsInBin.forEach((ean, qDto) -> flatStock.put(ean, qDto.quantity));
                    }
                    bins.add(new Bin(dto.id, dto.currentGridLocation, flatStock));
                }
            }
            return bins;
        } catch (IOException e) {
            System.err.println("Error, did not load bins.json: " + e.getMessage());
            return new ArrayList<>();
        }
    }
}