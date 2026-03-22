package com.Warehouse.Simulator.io;
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

/**
 * Deserialises the three core simulation data files — shipments, grids, and bins —
 * from JSON into their domain model equivalents.
 *
 * <p>Each {@code load*Json()} method opens its file, maps the raw JSON DTOs to
 * domain objects, applies any necessary normalisations (date fallbacks, port-ID
 * generation, stock flattening), and returns an empty list on I/O failure rather
 * than propagating an exception.
 *
 * <p>Two construction modes:
 * <ul>
 *   <li><b>Default</b> — hardcoded level-9 sample data paths, useful for quick
 *       local runs.</li>
 *   <li><b>Explicit</b> — caller supplies all three paths, intended for use with
 *       {@link ConfigLoader} in production.</li>
 * </ul>
 */
public class DataLoader {

    /** Path to the shipments JSON file. */
    private final String shipmentPath;

    /** Path to the grids JSON file. */
    private final String gridPath;

    /** Path to the bins JSON file. */
    private final String binPath;

    /** Shared Gson instance — stateless and thread-safe. */
    private final Gson gson = new Gson();

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Default constructor pointing at the bundled level-9 sample data.
     * Useful for local development and smoke tests.
     */
    public DataLoader() {
        this(
                "Data/sample-data/level9/shipments9.json",
                "Data/sample-data/level9/grids9.json",
                "Data/sample-data/level9/bins9.json"
        );
    }

    /**
     * Explicit constructor — use with {@link ConfigLoader} to supply
     * scenario-specific data paths at runtime.
     *
     * @param shipmentPath path to the shipments JSON file
     * @param gridPath     path to the grids JSON file
     * @param binPath      path to the bins JSON file
     */
    public DataLoader(String shipmentPath, String gridPath, String binPath) {
        this.shipmentPath = shipmentPath;
        this.gridPath     = gridPath;
        this.binPath      = binPath;
    }

    // =========================================================================
    // 1. Shipments
    // =========================================================================

    /**
     * Raw DTO mirroring the shipments JSON shape.
     * Two alternative date fields are supported for backwards compatibility:
     * {@code created_at} (preferred) and {@code shipmentDate} (legacy).
     */
    private static class ShipmentLoadDto {
        String id;
        Map<String, Integer> items;

        @SerializedName("created_at")
        String createdAt;

        /** Legacy date field — used only when {@code created_at} is absent. */
        @SerializedName("shipmentDate")
        String shipmentDate;

        @SerializedName("handlingFlags")
        Set<String> handlingFlags;

        @SerializedName("sortingDirection")
        String sortingDirection;
    }

    /**
     * Loads and deserialises the shipments file into {@link Shipment} domain objects.
     *
     * <p>Normalisations applied:
     * <ul>
     *   <li>Date: prefers {@code created_at}; falls back to {@code shipmentDate}.
     *       Shipments with neither field are skipped with a warning.</li>
     *   <li>Handling flags: defaults to an empty set if absent.</li>
     *   <li>Sorting direction: defaults to {@code "DEFAULT-DIR"} if absent.</li>
     * </ul>
     *
     * @return list of loaded shipments; empty list if the file cannot be read
     */
    public List<Shipment> loadShipmentsJson() {
        try (FileReader fileReader = new FileReader(shipmentPath)) {
            ShipmentLoadDto[] arr = gson.fromJson(fileReader, ShipmentLoadDto[].class);
            List<Shipment> shipments = new ArrayList<>();
            if (arr != null) {
                for (ShipmentLoadDto dto : arr) {
                    // Prefer created_at; fall back to legacy shipmentDate field
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
    //
    // grids.json uses "start"/"end" for shift times and "portIndex" for port
    // identification. portIndex may be a plain integer ("0") or a hyphenated
    // string ("port-1"). Both forms are normalised to a stable String port ID:
    //
    //   portIndex contains "-"  →  "{gridId}-{portIndex}"   e.g. "AS1-port-1"
    //   portIndex is numeric    →  "port-{gridId}-{index}"  e.g. "port-AS1-0"
    //
    // This ensures port IDs are unique across grids regardless of input format.
    // =========================================================================

    /** Raw DTO mirroring the top-level grid object in grids.json. */
    private static class GridLoadDto {
        String id;
        List<ShiftLoadDto> shifts;
    }

    /** Raw DTO for a single shift entry within a grid. */
    private static class ShiftLoadDto {
        /** Shift start time (ISO-8601). Mapped to {@link Shift#startAt}. */
        String start;
        /** Shift end time (ISO-8601). Mapped to {@link Shift#endAt}. */
        String end;
        List<PortConfigLoadDto> portConfig;
        List<Shift.BreakWindow> breaks;
    }

    /**
     * Raw DTO for a port config entry within a shift.
     * {@code portIndex} may arrive as a quoted string ({@code "port-1"}) or an
     * unquoted integer ({@code 0}); Gson deserialises both safely as {@code String}.
     */
    private static class PortConfigLoadDto {
        String portIndex;
        List<String> handlingFlags;
    }

    /**
     * Loads and deserialises the grids file into {@link Grid} domain objects.
     *
     * <p>Normalisations applied:
     * <ul>
     *   <li>Port IDs are synthesised from {@code portIndex} and {@code gridId}
     *       to guarantee uniqueness across grids (see class-level comment).</li>
     *   <li>Null {@code portIndex} defaults to {@code "0"}.</li>
     *   <li>Null handling flags default to an empty list.</li>
     *   <li>Null breaks list is ignored; an empty list is used instead.</li>
     * </ul>
     *
     * @return list of loaded grids; empty list if the file cannot be read
     */
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
                                    String idx = pDto.portIndex != null ? pDto.portIndex : "0";
                                    // Hyphenated portIndex → prefix with gridId for uniqueness
                                    // Plain numeric portIndex → use "port-{gridId}-{idx}" form
                                    pc.portId = idx.contains("-")
                                            ? gDto.id + "-" + idx
                                            : "port-" + gDto.id + "-" + idx;
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
    //
    // bins.json stores stock as { "EAN": { "quantity": N } } rather than a
    // flat { "EAN": N } map. QuantityDto unwraps the nested object; the result
    // is flattened to Map<String, Integer> before constructing the Bin.
    // =========================================================================

    /** Raw DTO mirroring the bin object in bins.json. */
    private static class BinLoadDto {
        String id;
        /** Grid the bin is physically located in at the start of the simulation. */
        String currentGridLocation;
        /** Nested stock map: EAN → { quantity: N }. Flattened during loading. */
        Map<String, QuantityDto> itemsInBin;
    }

    /**
     * Wrapper DTO for the nested quantity object in {@code itemsInBin}.
     * Exists solely because bins.json stores quantities as {@code {"quantity": N}}
     * rather than bare integers.
     */
    private static class QuantityDto {
        int quantity;
    }

    /**
     * Loads and deserialises the bins file into {@link Bin} domain objects.
     *
     * <p>The nested {@code { "quantity": N }} stock structure is flattened to a
     * plain {@code Map<String, Integer>} before the {@link Bin} is constructed.
     * Bins with a null {@code itemsInBin} field are created with empty stock.
     *
     * @return list of loaded bins; empty list if the file cannot be read
     */
    public List<Bin> loadBinsJson() {
        try (FileReader fileReader = new FileReader(binPath)) {
            BinLoadDto[] arr = gson.fromJson(fileReader, BinLoadDto[].class);
            List<Bin> bins = new ArrayList<>();
            if (arr != null) {
                for (BinLoadDto dto : arr) {
                    // Flatten { "EAN": { "quantity": N } } → { "EAN": N }
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