package com.Warehouse.Simulator.router;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import com.google.gson.Gson;

/**
 * INFRASTRUCTURE: RouterCaller
 *
 * Manages communication with the external router subprocess.
 * Serialises the current simulation state to JSON, writes it to the
 * router process's stdin, reads the JSON response from stdout, and
 * deserialises it back into Java objects.
 *
 * The router binary is launched as a fresh subprocess on every call.
 * stderr is kept separate from stdout so router error output does not
 * corrupt the JSON response.
 *
 * DTO aliases:
 *   Event classes reference types as RouterCaller.X (e.g. RouterCaller.State).
 *   Each inner alias class simply extends the matching RouterDTOs class so
 *   both names resolve to the same data structure without duplicating fields.
 */
public class RouterCaller {

    // =========================================================================
    // DTO aliases — events use RouterCaller.X, forwarded to RouterDTOs.X
    // =========================================================================

    /** Alias for {@link RouterDTOs.Pick}. Represents a single bin pick operation. */
    public static class Pick extends RouterDTOs.Pick {}

    /** Alias for {@link RouterDTOs.State}. The full warehouse state sent to the router. */
    public static class State extends RouterDTOs.State {}

    /** Alias for {@link RouterDTOs.ShipmentDto}. A shipment entry in the router backlog. */
    public static class ShipmentDto extends RouterDTOs.ShipmentDto {}

    /** Alias for {@link RouterDTOs.BinDto}. A bin stock entry sent to the router. */
    public static class StockBinDto extends RouterDTOs.BinDto {}

    /** Alias for {@link RouterDTOs.Assignment}. A routing decision returned by the router. */
    public static class Assignment extends RouterDTOs.Assignment {}

    /** Alias for {@link RouterDTOs.Response}. The root object of the router's JSON response. */
    public static class Response extends RouterDTOs.Response {}

    /**
     * Wraps the {@link RouterDTOs.State} in a named field so it can be passed
     * around as a single object and serialised into the router's expected
     * input envelope: {@code {"state": {...}}}.
     */
    public static class RouterInput {
        /** The warehouse state snapshot to send to the router. */
        public RouterDTOs.State state;

        public RouterInput(RouterDTOs.State state) { this.state = state; }
    }

    /**
     * Unchecked exception thrown when the router subprocess fails or returns
     * a response that cannot be parsed. Wraps the original cause when available
     * so the full stack trace is preserved for diagnostics.
     */
    public static class RouterException extends RuntimeException {
        public RouterException(String msg) { super(msg); }
        public RouterException(String msg, Throwable cause) { super(msg, cause); }
    }

    // =========================================================================
    // Subprocess communication
    // =========================================================================

    /** Filesystem path (or command) used to launch the router binary. */
    private final String routerPath;

    /** Gson instance for serialising the request and deserialising the response. */
    private final Gson gson = new Gson();

    /**
     * Creates a RouterCaller that will invoke the router at the given path.
     *
     * @param routerPath path to the router executable, e.g. "Data/router/router-linux-amd64"
     */
    public RouterCaller(String routerPath) {
        this.routerPath = routerPath;
    }

    /**
     * Invokes the router subprocess with the current simulation state and
     * returns the parsed assignment list.
     *
     * Protocol:
     *   1. Serialise {@code input} to JSON.
     *   2. Spawn the router process and write the JSON to its stdin.
     *   3. Read the router's full stdout response.
     *   4. Wait for the process to exit; throw if exit code is non-zero.
     *   5. Deserialise the JSON response and wrap it in a RouterCaller.Response.
     *
     * All non-RouterException exceptions (I/O errors, JSON parse failures, etc.)
     * are caught and re-thrown as RouterException so callers only need to handle
     * one exception type.
     *
     * @param input the full router input containing the warehouse state snapshot
     * @return parsed response containing the list of routing assignments
     * @throws RouterException if the subprocess exits with a non-zero code,
     *                         if I/O fails, or if the response JSON is malformed
     */
    public Response call(RouterInput input) {
        String json = gson.toJson(new RouterDTOs.Request(input.state));

        try {
            ProcessBuilder pb = new ProcessBuilder(routerPath);
            // Keep stderr separate so router error output does not appear in
            // the stdout stream that we parse as JSON.
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Write the serialised state to the router's stdin, then close
            // the stream to signal end-of-input to the router process.
            OutputStream stdin = process.getOutputStream();
            stdin.write(json.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            stdin.close();

            // Read the router's full JSON response from stdout line by line.
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            // A non-zero exit code means the router encountered an error;
            // its stderr output will have details but is not captured here.
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RouterException("Router exited with code " + exitCode);
            }

            // Deserialise the response and copy assignments into the alias type
            // so callers receive a RouterCaller.Response rather than a raw RouterDTOs.Response.
            RouterDTOs.Response raw = gson.fromJson(sb.toString(), RouterDTOs.Response.class);
            Response response = new Response();
            response.assignments = raw.assignments;
            return response;

        } catch (RouterException re) {
            // Re-throw directly so the RouterException message is not wrapped again.
            throw re;
        } catch (Exception e) {
            throw new RouterException("Router subprocess error: " + e.getMessage(), e);
        }
    }
}