package com.Warehouse.Simulator.router;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.annotations.SerializedName;

public class RouterDTOs {

    // 1. The Root Wrapper
    public static class Request {
        public State state;
        public Request(State state) { this.state = state; }
    }

    // 2. The Main State Object
    public static class State {
        public String now;
        
        @SerializedName("shipments_backlog")
        public List<ShipmentDto> shipmentsBacklog = new ArrayList<>();
        
        @SerializedName("stock_bins")
        public List<BinDto> stockBins = new ArrayList<>();
        
        @SerializedName("truck_arrival_schedules")
        public TruckArrivalWrapper truckArrivalSchedules = new TruckArrivalWrapper();
        
        public List<GridDto> grids = new ArrayList<>();
    }

    public static class TruckArrivalWrapper {
    public List<ScheduleEntry> schedules = new ArrayList<>();
    
    public static class ScheduleEntry {
        @SerializedName("sorting_direction")
        public String sortingDirection;
        @SerializedName("pull_times")
        public List<String> pullTimes;
        public List<String> weekdays;
    }
}

    public static class ShipmentDto {
        public String id;
        @SerializedName("created_at")
        public String createdAt;
        public Map<String, Integer> items;
        @SerializedName("handling_flags")
        public List<String> handlingFlags = new ArrayList<>();
        @SerializedName("sorting_direction") // kai turesim truck schedules
        public String sortingDirection;
    }

    public static class BinDto {
        @SerializedName("bin_id")
        public String binId;
        @SerializedName("grid_id")
        public String gridId;
        public Map<String, Integer> items;
    }

    public static class GridDto {
        public String id;
        public List<ShiftDto> shifts = new ArrayList<>();
    }

    public static class ShiftDto {
        @SerializedName("start_at")
        public String startAt;
        @SerializedName("end_at")
        public String endAt;
        @SerializedName("port_config")
        public List<PortConfigDto> portConfig = new ArrayList<>();
    }

    public static class PortConfigDto {
        @SerializedName("port_id")
        public String portId;
        @SerializedName("handling_flags")
        public List<String> handlingFlags = new ArrayList<>();
    }

    // cia jau kitam zingsniui simuliacijos enginui. Verciam JSON i java objects atgal
    public static class Response {
        public List<Assignment> assignments = new ArrayList<>();
    }

    public static class Assignment {
        @SerializedName("shipment_id")
        public String shipmentId;
        
        public int priority;
        
        @SerializedName("packing_grid")
        public String packingGrid;
        
        public List<Pick> picks = new ArrayList<>();
    }

    public static class Pick {
        public String ean;
        
        @SerializedName("bin_id")
        public String binId;
        
        public int qty;
    }
}