import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads simulation parameters from params.json.
 *
 * Currently handles:
 *   - truckArrivalSchedules: list of per-direction truck pull times and weekdays
 *
 * To add more param sections in the future, add fields to ParamsDto and
 * expose new load methods here.
 */
public class ParamsLoader {

    private final String paramsPath;
    private final Gson gson = new Gson();

    public ParamsLoader(String paramsPath) {
        this.paramsPath = paramsPath;
    }

    // -------------------------------------------------------------------------
    // Internal DTOs — mirrors params.json structure exactly
    // -------------------------------------------------------------------------

    private static class ParamsDto {
        @SerializedName("truckArrivalSchedules")
        TruckArrivalSchedulesDto truckArrivalSchedules;
    }

    private static class TruckArrivalSchedulesDto {
        List<TruckScheduleDto> schedules;
    }

    private static class TruckScheduleDto {
        String sortingDirection;
        List<String> pullTimes;
        List<String> weekdays;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parses params.json and returns all truck schedules.
     * Returns an empty list if the file is missing or has no schedules.
     */
    public List<TruckSchedule> loadTruckSchedules() {
        try (FileReader reader = new FileReader(paramsPath)) {
            ParamsDto params = gson.fromJson(reader, ParamsDto.class);
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

        } catch (IOException e) {
            System.err.println("Error loading params.json: " + e.getMessage());
            return new ArrayList<>();
        }
    }
}