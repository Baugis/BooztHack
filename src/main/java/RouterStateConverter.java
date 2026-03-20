import com.google.gson.Gson;
import java.util.List;


public class RouterStateConverter {
    private final Gson gson = new Gson();

    public String generateRouterJson(List<Shipment> activeShipments, List<Bin> allBins, List<Grid> allGrids, String currentSimTimeIso) {
        
        RouterDTOs.State stateDto = new RouterDTOs.State();
        stateDto.now = currentSimTimeIso; // e.g., "2026-03-01T09:00:00Z"

        // 1. Convert Shipments
        for (Shipment shipment : activeShipments) {
            // Only send shipments that haven't been routed/packed yet!
            if (shipment.getStatus() == Shipment.ShipmentStatus.RECEIVED) {
                RouterDTOs.ShipmentDto sDto = new RouterDTOs.ShipmentDto();
                sDto.id = shipment.getId();
                sDto.createdAt = shipment.createdAt;
                sDto.items = shipment.items; 
                // sDto.sortingDirection = s.getSortingDirection(); // prideti veliau Shipment.java!
                sDto.sortingDirection = "dir-1"; // Hardcoded fallback for now
                
                stateDto.shipmentsBacklog.add(sDto);
            }
        }

        // 2. Convert Bins
        for (Bin b : allBins) {
            RouterDTOs.BinDto bDto = new RouterDTOs.BinDto();
            bDto.binId = b.getBinId();
            bDto.gridId = b.getGridId();
            bDto.items = b.getStock(); // This perfectly flattens the map!
            
            stateDto.stockBins.add(bDto);
        }

        // 3. Convert Grids (Simplified for Level 1)
        for (Grid g : allGrids) {
            RouterDTOs.GridDto gDto = new RouterDTOs.GridDto();
            
            gDto.id = g.getId();; 
            
            // For Level 1, you can inject a dummy 24/7 shift so the router knows ports are open
            RouterDTOs.ShiftDto shiftDto = new RouterDTOs.ShiftDto();
            shiftDto.startAt = "2026-03-01T00:00:00Z";
            shiftDto.endAt = "2026-03-02T00:00:00Z";
            
            RouterDTOs.PortConfigDto portDto = new RouterDTOs.PortConfigDto();
            portDto.portId = "port-1";
            shiftDto.portConfig.add(portDto);
            
            gDto.shifts.add(shiftDto);
            stateDto.grids.add(gDto);
        }

        // Wrap it in the Request object and convert to JSON
        RouterDTOs.Request request = new RouterDTOs.Request(stateDto);
        return gson.toJson(request);
    }
}