package com.Warehouse.Simulator.router;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * DTOs: RouterDTOs
 *
 * Plain data-transfer objects used to serialise the simulation state into the
 * JSON payload sent to the external router subprocess (via stdin) and to
 * deserialise the router's JSON response (from stdout) back into Java objects.
 *
 * Data flow:
 *   Outbound (simulation -> router):
 *     Request -> State -> ShipmentDto / BinDto / GridDto / ShiftDto / PortConfigDto
 *
 *   Inbound (router -> simulation):
 *     Response -> Assignment -> Pick
 *
 * All field names follow the router's JSON schema. Where the Java convention
 * (camelCase) differs from the JSON convention (snake_case), a
 * {@code @SerializedName} annotation maps the two.
 *
 * These classes are intentionally simple POJOs with no logic — Gson populates
 * them directly via reflection.
 */
public class RouterDTOs {

    // =========================================================================
    // Outbound DTOs  (simulation -> router)
    // =========================================================================

    /**
     * Root wrapper for the router input payload.
     * Gson serialises this as {"state": {...}}.
     */
    public static class Request {
        /** The full warehouse state snapshot sent to the router. */
        public State state;

        public Request(State state) { this.state = state; }
    }

    /**
     * Top-level state object containing everything the router needs to make
     * routing decisions: current time, unrouted shipments, available bin stock,
     * truck schedules, and grid/shift/port configurations.
     */
    public static class State {

        /** Current simulation time as an ISO-8601 UTC string, e.g. "2026-03-01T09:00:00Z". */
        public String now;

        /** Shipments waiting to be routed (status RECEIVED). Maps to JSON "shipments_backlog". */
        @SerializedName("shipments_backlog")
        public List<ShipmentDto> shipmentsBacklog = new ArrayList<>();

        /** Current bin inventory across all grids. Maps to JSON "stock_bins". */
        @SerializedName("stock_bins")
        public List<BinDto> stockBins = new ArrayList<>();

        /** Truck arrival schedules used by the router for deadline-aware prioritisation.
         *  Maps to JSON "truck_arrival_schedules". */
        @SerializedName("truck_arrival_schedules")
        public TruckArrivalWrapper truckArrivalSchedules = new TruckArrivalWrapper();

        /** Grid definitions including shifts and port configurations. */
        public List<GridDto> grids = new ArrayList<>();
    }

    /**
     * Wrapper around the list of truck arrival schedules.
     * Exists to match the router's expected JSON structure:
     * {"truck_arrival_schedules": {"schedules": [...]}}.
     *
     * TODO: populate schedules from ParamsLoader.loadTruckSchedules() so the
     *       router can factor truck deadlines into its prioritisation logic.
     */
    public static class TruckArrivalWrapper {
        public List<TruckScheduleDto> schedules = new ArrayList<>();
    }

    public static class TruckScheduleDto {
        @SerializedName("sorting_direction")
        public String sortingDirection;

        @SerializedName("pull_times")
        public List<String> pullTimes = new ArrayList<>();

        public List<String> weekdays = new ArrayList<>();
    }

    /**
     * Represents a single shipment in the router backlog.
     * Contains all fields the router needs to prioritise and assign the shipment.
     */
    public static class ShipmentDto {

        /** Unique shipment identifier. */
        public String id;

        /** ISO-8601 creation timestamp. Used by the router for FIFO ordering. Maps to JSON "created_at". */
        @SerializedName("created_at")
        public String createdAt;

        /** Items in this order: EAN barcode -> quantity required. */
        public Map<String, Integer> items;

        /** Special handling requirements, e.g. ["fragile", "heavy"]. Maps to JSON "handling_flags". */
        @SerializedName("handling_flags")
        public List<String> handlingFlags = new ArrayList<>();

        /** Sorting direction determining which truck will carry this shipment.
         *  Maps to JSON "sorting_direction". */
        @SerializedName("sorting_direction")
        public String sortingDirection;
    }

    /**
     * Represents a single bin's current location and stock level.
     * Sent to the router so it can plan picks and minimise inter-grid transfers.
     */
    public static class BinDto {

        /** Unique bin identifier. Maps to JSON "bin_id". */
        @SerializedName("bin_id")
        public String binId;

        /** ID of the grid where this bin currently resides. Maps to JSON "grid_id". */
        @SerializedName("grid_id")
        public String gridId;

        /** Current stock in this bin: EAN barcode -> quantity available. */
        public Map<String, Integer> items;
    }

    /**
     * Represents a grid and its active shift windows.
     * Sent to the router so it knows which ports are available during the
     * current simulation time and what handling flags each port supports.
     */
    public static class GridDto {

        /** Unique grid identifier, e.g. "AS1". */
        public String id;

        /** Shift windows active for this grid, each defining ports and break schedules. */
        public List<ShiftDto> shifts = new ArrayList<>();
    }

    /**
     * Represents a single shift window within a grid.
     * Defines when the shift is active and which ports operate during it.
     */
    public static class ShiftDto {

        /** Shift start time as an ISO-8601 UTC string. Maps to JSON "start_at". */
        @SerializedName("start_at")
        public String startAt;

        /** Shift end time as an ISO-8601 UTC string. Maps to JSON "end_at". */
        @SerializedName("end_at")
        public String endAt;

        /** Ports active during this shift and their handling capabilities.
         *  Maps to JSON "port_config". */
        @SerializedName("port_config")
        public List<PortConfigDto> portConfig = new ArrayList<>();
    }

    /**
     * Configuration for a single port within a shift.
     * Tells the router which shipment types (handling flags) this port can accept.
     */
    public static class PortConfigDto {

        /** Unique port identifier, e.g. "port-AS1-0". Maps to JSON "port_id". */
        @SerializedName("port_id")
        public String portId;

        /** Handling flags supported by this port, e.g. ["fragile", "heavy"].
         *  The router only assigns a shipment to a grid if at least one port
         *  in that grid supports all of the shipment's required flags.
         *  Maps to JSON "handling_flags". */
        @SerializedName("handling_flags")
        public List<String> handlingFlags = new ArrayList<>();
    }

    // =========================================================================
    // Inbound DTOs  (router -> simulation)
    // =========================================================================

    /**
     * Root object of the router's JSON response.
     * Contains the list of routing assignments to be applied by
     * ShipmentRouterTriggered.
     */
    public static class Response {
        /** Routing assignments returned by the router, one per routed shipment. */
        public List<Assignment> assignments = new ArrayList<>();
    }

    /**
     * A single routing decision returned by the router for one shipment.
     * Tells the simulation which grid to pack the shipment in, what priority
     * to give it, and the ordered list of bin picks required to fulfil it.
     */
    public static class Assignment {

        /** ID of the shipment this assignment applies to. Maps to JSON "shipment_id". */
        @SerializedName("shipment_id")
        public String shipmentId;

        /** Router-calculated priority score. Higher values are processed sooner. */
        public int priority;

        /** ID of the grid where this shipment should be packed. Maps to JSON "packing_grid". */
        @SerializedName("packing_grid")
        public String packingGrid;

        /** Ordered list of individual bin picks required to fulfil this shipment.
         *  Ports work through this list one pick at a time. */
        public List<Pick> picks = new ArrayList<>();
    }

    /**
     * A single pick operation within a routing assignment.
     * Instructs the port to take a specific quantity of one EAN from one bin.
     */
    public static class Pick {

        /** EAN barcode of the item to pick. */
        public String ean;

        /** ID of the bin to pick from. Maps to JSON "bin_id". */
        @SerializedName("bin_id")
        public String binId;

        /** Quantity of the EAN to pick from this bin. */
        public int qty;
    }
}