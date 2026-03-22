package com.Warehouse.Simulator.io;

import java.io.FileReader;
import java.io.IOException;

import com.google.gson.Gson;

/**
 * Loads simulation configuration from a single {@code config.json} file.
 *
 * <p>Parses the file once at construction time and exposes typed accessors.
 * All file paths returned by the {@code get*Path()} methods are resolved
 * against {@code dataDir} if one is configured, so callers always receive
 * ready-to-use absolute (or reliably relative) paths.
 *
 * <p>Example {@code config.json} structure:
 * <pre>{@code
 * {
 *   "simulationDays": 3,
 *   "epochDate": "2026-03-02T00:00:00Z",
 *   "dataDir": "data/scenario_01",
 *   "files": {
 *     "shipments": "shipments.json",
 *     "grids":     "grids.json",
 *     "bins":      "bins.json",
 *     "params":    "params.json"
 *   }
 * }
 * }</pre>
 *
 * <p>Usage:
 * <pre>{@code
 * ConfigLoader cfg = new ConfigLoader("config.json");
 * double duration  = cfg.getSimDurationSeconds();
 * String epoch     = cfg.getEpochDate();
 * String ships     = cfg.getShipmentsPath();
 * }</pre>
 */
public class ConfigLoader {

    /** Parsed config, held for the lifetime of this loader. */
    private final ConfigDto dto;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Parses the config file eagerly. Fails fast if the file is missing or
     * cannot be deserialised, so misconfiguration is caught at startup rather
     * than mid-simulation.
     *
     * @param configPath path to the JSON configuration file
     * @throws RuntimeException wrapping the underlying {@link IOException}
     *                          if the file cannot be read or parsed
     */
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

    /**
     * Total simulation duration in seconds, derived as
     * {@code simulationDays × 86 400}.
     *
     * @return simulation duration in seconds
     */
    public double getSimDurationSeconds() {
        return dto.simulationDays * 86_400.0;
    }

    /**
     * ISO-8601 epoch start date-time that anchors simulation time {@code 0}.
     *
     * @return epoch string, e.g. {@code "2026-03-02T00:00:00Z"}
     */
    public String getEpochDate() {
        return dto.epochDate;
    }

    /** @return resolved path to the shipments data file */
    public String getShipmentsPath() { return resolve(dto.files.shipments); }

    /** @return resolved path to the grids data file */
    public String getGridsPath()     { return resolve(dto.files.grids);     }

    /** @return resolved path to the bins data file */
    public String getBinsPath()      { return resolve(dto.files.bins);      }

    /** @return resolved path to the simulation parameters file */
    public String getParamsPath()    { return resolve(dto.files.params);    }

    // -------------------------------------------------------------------------
    // Internal DTOs
    // -------------------------------------------------------------------------

    /**
     * Top-level structure of {@code config.json}.
     * Fields are populated by Gson via reflection; names must match JSON keys exactly.
     */
    private static class ConfigDto {
        /** Number of simulated days; multiplied by 86 400 to get seconds. */
        int    simulationDays;

        /** ISO-8601 date-time string that maps to simulation time 0. */
        String epochDate;

        /**
         * Optional base directory prepended to all filenames in {@link Files}.
         * If absent or empty, filenames are used as-is.
         */
        String dataDir;

        /** Filenames for each data source (resolved against {@link #dataDir}). */
        Files  files;
    }

    /**
     * Filenames for each simulation data source, nested under {@code "files"}
     * in the config JSON.
     */
    private static class Files {
        String shipments;
        String grids;
        String bins;
        String params;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Prepends {@code dataDir} to a filename if one is configured.
     * Returns the filename unchanged when {@code dataDir} is absent or empty.
     *
     * @param filename bare filename from the {@link Files} DTO
     * @return fully resolved path ready for file I/O
     */
    private String resolve(String filename) {
        String dir = dto.dataDir;
        if (dir == null || dir.isEmpty()) return filename;
        return dir + "/" + filename;
    }
}