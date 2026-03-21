import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads simulation parameters from params.json.
 *
 * Handles:
 *   - truckArrivalSchedules: per-direction truck pull times and weekdays
 *   - conveyors: inter-grid transfer times
 */
public class ParamsLoader {

    private final String paramsPath;
    private final Gson gson = new Gson();

    public ParamsLoader(String paramsPath) {
        this.paramsPath = paramsPath;
    }

    // -------------------------------------------------------------------------
    // Internal DTOs
    // -------------------------------------------------------------------------

    private static class ParamsDto {
        @SerializedName("truckArrivalSchedules")
        TruckArrivalSchedulesDto truckArrivalSchedules;

        @SerializedName("conveyors")
        List<ConveyorDto> conveyors;
    }

    private static class TruckArrivalSchedulesDto {
        List<TruckScheduleDto> schedules;
    }

    private static class TruckScheduleDto {
        String sortingDirection;
        List<String> pullTimes;
        List<String> weekdays;
    }

    private static class ConveyorDto {
        String from;
        String to;
        int transferTimeSeconds;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

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
            List<String> weekdays = dto.weekdays != null ? dto.weekdays : new ArrayList<>();
            result.add(new TruckSchedule(dto.sortingDirection, dto.pullTimes, weekdays));
        }
        return result;
    }

    /**
     * Returns a map of "fromGridId->toGridId" -> transferTimeSeconds.
     * e.g. "AS1->AS2" -> 300
     * Returns an empty map if no conveyors are defined.
     */
    public Map<String, Double> loadConveyors() {
        ParamsDto params = readParams();
        Map<String, Double> result = new HashMap<>();
        if (params == null || params.conveyors == null) {
            System.err.println("Warning: no conveyors found in " + paramsPath
                    + " — using default 300s transfer delay");
            return result;
        }
        for (ConveyorDto dto : params.conveyors) {
            if (dto.from == null || dto.to == null) continue;
            String key = dto.from + "->" + dto.to;
            result.put(key, (double) dto.transferTimeSeconds);
            System.out.printf("Conveyor loaded: %s -> %s (%ds)%n",
                    dto.from, dto.to, dto.transferTimeSeconds);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private ParamsDto readParams() {
        try (FileReader reader = new FileReader(paramsPath)) {
            return gson.fromJson(reader, ParamsDto.class);
        } catch (IOException e) {
            System.err.println("Error loading params.json: " + e.getMessage());
            return null;
        }
    }
}