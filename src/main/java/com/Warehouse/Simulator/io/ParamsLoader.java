package com.Warehouse.Simulator.io;

import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.Warehouse.Simulator.engine.Simulation;
import com.Warehouse.Simulator.engine.events.TruckSchedule;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * LOADER: ParamsLoader
 *
 * Reads simulation parameters from a params.json file and converts them into
 * the domain objects used by the simulation engine.
 *
 * Handles two categories of parameters:
 *   - Truck arrival schedules: per sorting-direction pull times and active weekdays,
 *     converted into {@link TruckSchedule} instances for use by Main and TruckArrived.
 *   - Conveyor delays: inter-grid transfer times in seconds, registered with
 *     {@link Simulation#registerConveyors(Map)} before the simulation runs.
 *
 * Two conveyor JSON formats are supported for backwards compatibility:
 *   - Level 8: top-level "conveyors" array with "transferTimeSeconds" field.
 *   - Level 9: "transfersConveyors.durations" array with "duration" field.
 *     In this format the reverse direction (B->A) is also registered automatically
 *     unless an explicit reverse entry is already present.
 *
 * If the params file cannot be read, methods return empty collections and log
 * a warning to stderr rather than throwing.
 */
public class ParamsLoader {

    /** Path to the params.json file passed to the constructor. */
    private final String paramsPath;

    /** Gson instance used to deserialise the params file. */
    private final Gson gson = new Gson();

    /**
     * Creates a ParamsLoader that reads from the given file path.
     *
     * @param paramsPath path to the params.json file, e.g. "Data/sample-data/level9/params9.json"
     */
    public ParamsLoader(String paramsPath) {
        this.paramsPath = paramsPath;
    }

    // -------------------------------------------------------------------------
    // Internal DTOs — mirror the params.json structure for Gson deserialisation
    // -------------------------------------------------------------------------

    /** Root params.json object. Supports both Level 8 and Level 9 conveyor formats. */
    private static class ParamsDto {

        @SerializedName("truckArrivalSchedules")
        TruckArrivalSchedulesDto truckArrivalSchedules;

        /** Level 8 conveyor format: flat array with transferTimeSeconds. */
        @SerializedName("conveyors")
        List<ConveyorDto> conveyors;

        /** Level 9 conveyor format: nested object with durations array. */
        @SerializedName("transfersConveyors")
        TransfersConveyorsDto transfersConveyors;

        @SerializedName("gridBinDelivery")
        GridBinDeliveryDto gridBinDelivery;
    }

    /** Wrapper around the list of truck schedule entries. */
    private static class TruckArrivalSchedulesDto {
        List<TruckScheduleDto> schedules;
    }

    /** A single truck schedule entry from params.json. */
    private static class TruckScheduleDto {
        /** Sorting direction this schedule applies to, e.g. "dir-1". */
        String sortingDirection;

        /** Departure times in "HH:mm" format, e.g. ["10:00", "14:00"]. */
        List<String> pullTimes;

        /** Days of the week this schedule is active, e.g. ["Monday", "Tuesday"]. */
        List<String> weekdays;
    }

    /** A single conveyor entry in the Level 8 format. */
    private static class ConveyorDto {
        /** Source grid ID. */
        String from;

        /** Destination grid ID. */
        String to;

        /** One-way transfer time in seconds. */
        int transferTimeSeconds;
    }

    /** Wrapper for the Level 9 conveyor format. */
    private static class TransfersConveyorsDto {
        List<TransferDurationDto> durations;
    }

    /** A single conveyor entry in the Level 9 format. */
    private static class TransferDurationDto {
        /** Source grid ID. */
        String from;

        /** Destination grid ID. */
        String to;

        /**
         * Transfer time in seconds.
         * Note: field name is "duration" in Level 9, not "transferTimeSeconds" as in Level 8.
         */
        int duration;
    }

    private static class GridBinDeliveryDto {
        Map<String, Double> deliveryTimes;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Loads and returns all truck arrival schedules from params.json.
     *
     * Each valid entry is converted into a {@link TruckSchedule} instance.
     * Entries missing a sorting direction or pull times are skipped with a warning.
     * If the schedules section is absent entirely, an empty list is returned.
     *
     * @return list of truck schedules; never null, may be empty
     */
    public List<TruckSchedule> loadTruckSchedules() {
        ParamsDto params = readParams();
        List<TruckSchedule> result = new ArrayList<>();

        if (params == null
                || params.truckArrivalSchedules == null
                || params.truckArrivalSchedules.schedules == null) {
            System.err.println("Warning: no truckArrivalSchedules found in " + paramsPath);
            return result;
        }

        for (TruckScheduleDto dto : params.truckArrivalSchedules.schedules) {
            if (dto.sortingDirection == null || dto.pullTimes == null || dto.pullTimes.isEmpty()) {
                System.err.println("Warning: skipping incomplete truck schedule entry");
                continue;
            }
            // Default to an empty weekday list if omitted — schedule will never fire,
            // but avoids a NullPointerException in the day-matching loop in Main.
            List<String> weekdays = dto.weekdays != null ? dto.weekdays : new ArrayList<>();
            result.add(new TruckSchedule(dto.sortingDirection, dto.pullTimes, weekdays));
        }

        return result;
    }

    /**
     * Loads and returns conveyor transfer times from params.json.
     *
     * Supports both JSON formats (see class Javadoc). Both formats may be
     * present in the same file; entries from both are merged into the result map.
     * For Level 9 entries the reverse direction (B->A) is also registered unless
     * an explicit reverse entry already exists.
     *
     * Key format: "fromGridId->toGridId", e.g. "AS1->AS2".
     * Value: transfer time in seconds as a double.
     *
     * If no conveyors are found a warning is logged and an empty map is returned;
     * the simulation will fall back to its default transfer delay.
     *
     * @return map of directional conveyor keys to transfer times in seconds;
     *         never null, may be empty
     */
    public Map<String, Double> loadConveyors() {
        ParamsDto params = readParams();
        Map<String, Double> result = new HashMap<>();
        if (params == null) return result;

        // --- Level 8 format: top-level "conveyors" array ---
        if (params.conveyors != null) {
            for (ConveyorDto dto : params.conveyors) {
                if (dto.from == null || dto.to == null) continue;
                String key = dto.from + "->" + dto.to;
                result.put(key, (double) dto.transferTimeSeconds);
                System.out.printf("Conveyor loaded: %s -> %s (%ds)%n",
                        dto.from, dto.to, dto.transferTimeSeconds);
            }
        }

        // --- Level 9 format: "transfersConveyors.durations" array ---
        // The reverse direction is registered automatically so callers do not
        // need to add both A->B and B->A entries in the JSON.
        if (params.transfersConveyors != null && params.transfersConveyors.durations != null) {
            for (TransferDurationDto dto : params.transfersConveyors.durations) {
                if (dto.from == null || dto.to == null) continue;

                String key        = dto.from + "->" + dto.to;
                String reverseKey = dto.to   + "->" + dto.from;

                result.put(key, (double) dto.duration);
                // Only register the reverse if not already explicitly defined.
                result.putIfAbsent(reverseKey, (double) dto.duration);

                System.out.printf("Conveyor loaded: %s <-> %s (%ds)%n",
                        dto.from, dto.to, dto.duration);
            }
        }

        if (result.isEmpty()) {
            System.err.println("Warning: no conveyors found in " + paramsPath
                    + " — using default 300s transfer delay");
        }

        return result;
    }

    // Pridėk čia naują metodą:
    public Map<String, Double> loadDeliveryTimes() {
        ParamsDto params = readParams();
        Map<String, Double> result = new HashMap<>();

        if (params == null || params.gridBinDelivery == null
                || params.gridBinDelivery.deliveryTimes == null) {
            System.err.println("Warning: no gridBinDelivery found — using defaults");
            result.put("AS1", 6.0);
            result.put("AS2", 4.0);
            result.put("AS3", 5.0);
            return result;
        }

        return params.gridBinDelivery.deliveryTimes;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Reads and deserialises the params.json file into a {@link ParamsDto}.
     * Returns null and logs an error if the file cannot be read or parsed,
     * allowing callers to handle the absence gracefully.
     *
     * @return parsed ParamsDto, or null on failure
     */
    private ParamsDto readParams() {
        try (FileReader reader = new FileReader(paramsPath)) {
            return gson.fromJson(reader, ParamsDto.class);
        } catch (IOException e) {
            System.err.println("Error loading params.json: " + e.getMessage());
            return null;
        }
    }
}