import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import com.google.gson.Gson;

/**
 * Wraps the external router subprocess and exposes the same DTO types
 * that event classes already reference (Pick, State, Assignment, etc.).
 *
 * Inner-class aliases forward to RouterDTOs so both names work.
 */
public class RouterCaller {

    // -------------------------------------------------------------------------
    // DTO aliases — events use RouterCaller.X, these delegate to RouterDTOs.X
    // -------------------------------------------------------------------------

    public static class Pick extends RouterDTOs.Pick {}

    public static class State extends RouterDTOs.State {}

    public static class ShipmentDto extends RouterDTOs.ShipmentDto {}

    public static class StockBinDto extends RouterDTOs.BinDto {}

    public static class Assignment extends RouterDTOs.Assignment {}

    public static class Response extends RouterDTOs.Response {}

    public static class RouterInput {
        public RouterDTOs.State state;
        public RouterInput(RouterDTOs.State state) { this.state = state; }
    }

    public static class RouterException extends RuntimeException {
        public RouterException(String msg) { super(msg); }
        public RouterException(String msg, Throwable cause) { super(msg, cause); }
    }

    // -------------------------------------------------------------------------
    // Subprocess communication
    // -------------------------------------------------------------------------

    private final String routerPath;
    private final Gson gson = new Gson();

    public RouterCaller(String routerPath) {
        this.routerPath = routerPath;
    }

    /**
     * Sends the current simulation state to the router process via stdin,
     * reads the JSON response from stdout, and returns the parsed assignments.
     *
     * @param input  the full router input (state wrapper)
     * @return       parsed response containing assignment list
     * @throws RouterException if the subprocess fails or returns bad JSON
     */
    public Response call(RouterInput input) {
        String json = gson.toJson(new RouterDTOs.Request(input.state));
        try {
            ProcessBuilder pb = new ProcessBuilder(routerPath);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Write JSON to router's stdin
            OutputStream stdin = process.getOutputStream();
            stdin.write(json.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            stdin.close();

            // Read router's stdout
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RouterException("Router exited with code " + exitCode);
            }

            RouterDTOs.Response raw = gson.fromJson(sb.toString(), RouterDTOs.Response.class);
            // Wrap into RouterCaller.Response (which extends RouterDTOs.Response)
            Response response = new Response();
            response.assignments = raw.assignments;
            return response;

        } catch (RouterException re) {
            throw re;
        } catch (Exception e) {
            throw new RouterException("Router subprocess error: " + e.getMessage(), e);
        }
    }
}