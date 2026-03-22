package com.Warehouse.Simulator.router;
import com.Warehouse.Simulator.model.*;
import com.google.gson.Gson;
import java.util.List;

/**
 * UTILITY: RouterStateConverter
 *
 * Converts the current simulation state into the JSON payload expected by the
 * external router subprocess.
 *
 * NOTE: This class is a legacy Level-1 helper and is no longer used in the main
 * simulation flow. State serialisation is now handled directly inside
 * ShipmentRouterTriggered, which builds RouterCaller.State from live simulation
 * data and passes it to RouterCaller. This class is retained for reference but
 * should not be called in production code.
 *
 * Known limitations (not fixed because the class is superseded):
 *   - sortingDirection is hardcoded to "dir-1" for all shipments.
 *   - Grid shifts are replaced with a dummy 24/7 window (Level-1 simplification).
 *   - All bins are included regardless of stock level.
 */
public class RouterStateConverter {

    /** Gson instance used to serialise the state DTO to JSON. */
    private final Gson gson = new Gson();

    /**
     * Builds a JSON string representing the current warehouse state for the router.
     *
     * The output follows the router's expected input schema:
     * a {@code Request} wrapper containing a {@code State} object with
     * shipment backlog, bin stock, and grid configurations.
     *
     * Only shipments in RECEIVED status are included in the backlog — shipments
     * that are already routed, picking, or packed are excluded.
     *
     * @param activeShipments list of all shipments currently in the simulation
     * @param allBins         list of all bins across all grids
     * @param allGrids        list of all grids in the simulation
     * @param currentSimTimeIso current simulation time as an ISO-8601 string,
     *                          e.g. "2026-03-01T09:00:00Z"
     * @return JSON string ready to be written to the router's stdin
     */
    public String generateRouterJson(List<Shipment> activeShipments,
                                     List<Bin> allBins,
                                     List<Grid> allGrids,
                                     String currentSimTimeIso) {

        RouterDTOs.State stateDto = new RouterDTOs.State();
        stateDto.now = currentSimTimeIso;

        // --- 1. Shipment backlog ---
        // Only RECEIVED shipments are sent; everything else is already in progress.
        for (Shipment shipment : activeShipments) {
            if (shipment.getStatus() == Shipment.ShipmentStatus.RECEIVED) {
                RouterDTOs.ShipmentDto sDto = new RouterDTOs.ShipmentDto();
                sDto.id        = shipment.getId();
                sDto.createdAt = shipment.createdAt;
                sDto.items     = shipment.items;

                // TODO: replace with shipment.getSortingDirection() once
                //       sortingDirection is reliably populated on all shipments.
                sDto.sortingDirection = "dir-1";

                stateDto.shipmentsBacklog.add(sDto);
            }
        }

        // --- 2. Bin stock ---
        // All bins are included so the router knows what inventory is available
        // and where it is located.
        for (Bin b : allBins) {
            RouterDTOs.BinDto bDto = new RouterDTOs.BinDto();
            bDto.binId  = b.getBinId();
            bDto.gridId = b.getGridId();
            bDto.items  = b.getStock();
            stateDto.stockBins.add(bDto);
        }

        // --- 3. Grid configurations (Level-1 simplification) ---
        // Real shift data is not yet wired up here. A dummy 24/7 shift is
        // injected for each grid so the router treats all ports as always open.
        // TODO: replace with actual shift data from grid.getShifts().
        for (Grid g : allGrids) {
            RouterDTOs.GridDto gDto = new RouterDTOs.GridDto();
            gDto.id = g.getId();

            RouterDTOs.ShiftDto shiftDto = new RouterDTOs.ShiftDto();
            shiftDto.startAt = "2026-03-01T00:00:00Z"; // dummy 24/7 window
            shiftDto.endAt   = "2026-03-02T00:00:00Z";

            RouterDTOs.PortConfigDto portDto = new RouterDTOs.PortConfigDto();
            portDto.portId = "port-1"; // dummy port — real ports not wired up here
            shiftDto.portConfig.add(portDto);

            gDto.shifts.add(shiftDto);
            stateDto.grids.add(gDto);
        }

        // Wrap in the Request envelope and serialise to JSON.
        RouterDTOs.Request request = new RouterDTOs.Request(stateDto);
        return gson.toJson(request);
    }
}