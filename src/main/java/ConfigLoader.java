import com.google.gson.Gson;
import java.io.FileReader;
import java.io.IOException;

/**
 * Loads simulation configuration from a single config.json file.
 *
 * Usage:
 *   ConfigLoader cfg = new ConfigLoader("config.json");
 *   double duration = cfg.getSimDurationSeconds();
 *   String epoch    = cfg.getEpochDate();
 *   String ships    = cfg.getShipmentsPath();
 *   ...
 */
public class ConfigLoader {

    private final ConfigDto dto;

    public ConfigLoader(String configPath) {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(configPath)) {
            this.dto = gson.fromJson(reader, ConfigDto.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config: " + configPath, e);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Total simulation duration in seconds (simulationDays * 86400). */
    public double getSimDurationSeconds() {
        return dto.simulationDays * 86_400.0;
    }

    /** ISO-8601 epoch start date, e.g. "2026-03-02T00:00:00Z". */
    public String getEpochDate() {
        return dto.epochDate;
    }

    public String getShipmentsPath() { return resolve(dto.files.shipments); }
    public String getGridsPath()     { return resolve(dto.files.grids);     }
    public String getBinsPath()      { return resolve(dto.files.bins);      }
    public String getParamsPath()    { return resolve(dto.files.params);    }

    // -------------------------------------------------------------------------
    // Internal DTOs
    // -------------------------------------------------------------------------

    private static class ConfigDto {
        int simulationDays;
        String epochDate;
        String dataDir;
        Files files;
    }

    private static class Files {
        String shipments;
        String grids;
        String bins;
        String params;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String resolve(String filename) {
        String dir = dto.dataDir;
        if (dir == null || dir.isEmpty()) return filename;
        return dir + "/" + filename;
    }
}