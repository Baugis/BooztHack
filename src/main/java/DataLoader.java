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

    private final String shipmentPath;
    private final String gridPath;
    private final String binPath;

    private final Gson gson = new Gson();

    /** Default constructor — points at level7 sample data. */
    public DataLoader() {
        this(
                "Data/sample-data/level7/shipments.json",
                "Data/sample-data/level7/grids.json",
                "Data/sample-data/level7/bins.json"
        );
    }

    /** Explicit constructor — pass any paths you like. */
    public DataLoader(String shipmentPath, String gridPath, String binPath) {
        this.shipmentPath = shipmentPath;
        this.gridPath     = gridPath;
        this.binPath      = binPath;
    }

    // =========================================================================
    // 1. Shipments
    // =========================================================================

    private static class ShipmentLoadDto {
        String id;
        Map<String, Integer> items;

        @SerializedName("created_at")
        String createdAt;

        @SerializedName("shipmentDate")
        String shipmentDate;

        @SerializedName("handlingFlags")
        Set<String> handlingFlags;

        @SerializedName("sortingDirection")
        String sortingDirection;
    }

    public List<Shipment> loadShipmentsJson() {
        try (FileReader fileReader = new FileReader(shipmentPath)) {
            ShipmentLoadDto[] arr = gson.fromJson(fileReader, ShipmentLoadDto[].class);
            List<Shipment> shipments = new ArrayList<>();
            if (arr != null) {
                for (ShipmentLoadDto dto : arr) {
                    String date = dto.createdAt != null ? dto.createdAt : dto.shipmentDate;
                    if (date == null) {
                        System.err.println("Warning: shipment " + dto.id + " has no date — skipping");
                        continue;
                    }
                    Set<String> flags = dto.handlingFlags != null ? dto.handlingFlags : new HashSet<>();
                    String dir = dto.sortingDirection != null ? dto.sortingDirection : "DEFAULT-DIR";
                    shipments.add(new Shipment(dto.id, dto.items, date, 0.0, flags, dir));
                }
            }
            return shipments;
        } catch (IOException e) {
            System.err.println("Error loading shipments.json: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // =========================================================================
    // 2. Grids
    //    grids.json uses "start"/"end" and portConfig with "portIndex" (int).
    //    We map portIndex -> "port-{gridId}-{portIndex}" to get a stable String ID.
    // =========================================================================

    /** Raw DTO that mirrors the actual grids.json shape. */
    private static class GridLoadDto {
        String id;
        List<ShiftLoadDto> shifts;
    }

    private static class ShiftLoadDto {
        String start;
        String end;
        List<PortConfigLoadDto> portConfig;
        List<Shift.BreakWindow> breaks;
    }

    private static class PortConfigLoadDto {
        int portIndex;
        List<String> handlingFlags;
    }

    public List<Grid> loadGridsJson() {
        try (FileReader fileReader = new FileReader(gridPath)) {
            GridLoadDto[] arr = gson.fromJson(fileReader, GridLoadDto[].class);
            List<Grid> grids = new ArrayList<>();
            if (arr != null) {
                for (GridLoadDto gDto : arr) {
                    List<Shift> shifts = new ArrayList<>();
                    if (gDto.shifts != null) {
                        for (ShiftLoadDto sDto : gDto.shifts) {
                            Shift shift = new Shift();
                            shift.startAt = sDto.start;
                            shift.endAt   = sDto.end;

                            if (sDto.portConfig != null) {
                                for (PortConfigLoadDto pDto : sDto.portConfig) {
                                    Shift.PortConfig pc = new Shift.PortConfig();
                                    // Convert integer portIndex -> stable string ID
                                    pc.portId = "port-" + gDto.id + "-" + pDto.portIndex;
                                    pc.handlingFlags = pDto.handlingFlags != null
                                            ? pDto.handlingFlags : new ArrayList<>();
                                    shift.portConfig.add(pc);
                                }
                            }

                            if (sDto.breaks != null) {
                                shift.breaks.addAll(sDto.breaks);
                            }

                            shifts.add(shift);
                        }
                    }
                    grids.add(new Grid(gDto.id, shifts));
                }
            }
            return grids;
        } catch (IOException e) {
            System.err.println("Error loading grids.json: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // =========================================================================
    // 3. Bins
    // =========================================================================

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
            BinLoadDto[] arr = gson.fromJson(fileReader, BinLoadDto[].class);
            List<Bin> bins = new ArrayList<>();
            if (arr != null) {
                for (BinLoadDto dto : arr) {
                    Map<String, Integer> flatStock = new HashMap<>();
                    if (dto.itemsInBin != null) {
                        dto.itemsInBin.forEach((ean, qDto) -> flatStock.put(ean, qDto.quantity));
                    }
                    bins.add(new Bin(dto.id, dto.currentGridLocation, flatStock));
                }
            }
            return bins;
        } catch (IOException e) {
            System.err.println("Error loading bins.json: " + e.getMessage());
            return new ArrayList<>();
        }
    }
}