package com.energysim.controller;

import com.energysim.config.NeighbourhoodConfig;
import com.energysim.model.SimulationSnapshot;
import com.energysim.service.SimulationEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for driving the neighbourhood energy simulation from the
 * frontend. The frontend polls / advances the simulation one tick at a
 * time via {@code /step} to animate it, and can inspect or change the
 * neighbourhood's generation configuration via {@code /config}.
 *
 * <p>Interactive docs: {@code /swagger-ui.html} · raw spec: {@code /v3/api-docs}
 */
@Tag(name = "Simulation", description = "Advance time, read the current/historical state, and configure the neighbourhood")
@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private final SimulationEngine engine;

    public SimulationController(SimulationEngine engine) {
        this.engine = engine;
    }

    @Operation(summary = "Get current state",
            description = "Returns the current simulation snapshot (date/time, weather, live and "
                    + "cumulative energy totals, every house, every public charger, and recent history) "
                    + "without advancing time.")
    @GetMapping("/state")
    public SimulationSnapshot state() {
        return engine.currentSnapshot();
    }

    @Operation(summary = "Advance one tick",
            description = "Advances the simulation by one tick (size set by the configured stepMinutes, "
                    + "10 minutes by default) and returns the resulting snapshot.")
    @PostMapping("/step")
    public SimulationSnapshot step() {
        return engine.step();
    }

    @Operation(summary = "Fast-forward several ticks",
            description = "Advances the simulation by `count` ticks in one call (capped at 500) and "
                    + "returns only the final snapshot — useful for skipping ahead several hours at once.")
    @PostMapping("/step/{count}")
    public SimulationSnapshot stepMany(
            @Parameter(description = "Number of ticks to advance (1-500)", example = "24")
            @PathVariable int count) {
        SimulationSnapshot last = engine.currentSnapshot();
        int n = Math.max(1, Math.min(count, 500));
        for (int i = 0; i < n; i++) {
            last = engine.step();
        }
        return last;
    }

    @Operation(summary = "Regenerate the neighbourhood",
            description = "Rebuilds the neighbourhood from the current configuration (new random houses "
                    + "unless a fixed seed is set) and restarts the simulation at day 1, 00:00.")
    @PostMapping("/reset")
    public SimulationSnapshot reset() {
        return engine.reset();
    }

    @Operation(summary = "Get current configuration",
            description = "Returns the neighbourhood generation configuration currently in effect — house "
                    + "count, asset proportions/size ranges, tick size, public charger roster, and the "
                    + "random seed actually used for the current neighbourhood (so this run can be "
                    + "reproduced later by feeding that seed back in).")
    @GetMapping("/config")
    public NeighbourhoodConfig config() {
        return engine.getConfig();
    }

    @Operation(summary = "Update configuration and regenerate",
            description = "Merges the given fields onto the current configuration (any subset of "
                    + "NeighbourhoodConfig — omitted fields keep their current value), then regenerates "
                    + "the neighbourhood from the result.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = @ExampleObject(
                    name = "Partial update",
                    value = "{\n"
                            + "  \"seed\": 42,\n"
                            + "  \"stepMinutes\": 15,\n"
                            + "  \"houseCount\": 40,\n"
                            + "  \"heatPumpProbability\": 0.6,\n"
                            + "  \"pvProbability\": 0.65,\n"
                            + "  \"evChargerProbability\": 0.5\n"
                            + "}")))
    @ApiResponse(responseCode = "400", description = "Invalid configuration",
            content = @Content(schema = @Schema(example = "{\"error\": \"Invalid neighbourhood configuration: ...\"}")))
    @PostMapping("/config")
    public SimulationSnapshot updateConfig(@RequestBody Map<String, Object> updates) {
        return engine.applyConfigUpdate(updates);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadConfig(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage());
    }
}
