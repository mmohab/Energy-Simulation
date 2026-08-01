package com.energysim.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests hitting the real REST API against the real (shared,
 * singleton) SimulationEngine/NeighbourhoodConfig beans. Every test resets
 * the simulation first via {@code @BeforeEach} so results don't depend on
 * execution order — but {@code /reset} only regenerates *from* the current
 * config, it doesn't clear config fields back to their defaults. Since
 * several tests here POST config changes (seed, startDate/startTime,
 * houseCount...) that would otherwise leak into whichever test runs next,
 * the class is annotated {@code @DirtiesContext} to get a fresh Spring
 * context — and therefore a fresh, default-valued config bean — after every
 * test method. Slower than reusing one context, but correctness here
 * matters more than speed for a test suite this small.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SimulationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetSimulation() throws Exception {
        mockMvc.perform(post("/api/simulation/reset")).andExpect(status().isOk());
    }

    @Test
    void getStateReturnsFreshSnapshot() throws Exception {
        mockMvc.perform(get("/api/simulation/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tick").value(0))
                .andExpect(jsonPath("$.day").value(1))
                .andExpect(jsonPath("$.timeLabel").value("00:00"))
                .andExpect(jsonPath("$.houses").isArray())
                .andExpect(jsonPath("$.publicChargers").isArray())
                .andExpect(jsonPath("$.houses.length()").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void stepAdvancesTimeByOneTick() throws Exception {
        mockMvc.perform(post("/api/simulation/step"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tick").value(1));
    }

    @Test
    void stepManyAdvancesByRequestedCount() throws Exception {
        mockMvc.perform(post("/api/simulation/step/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tick").value(5));
    }

    @Test
    void stepManyIsCappedAtFiveHundred() throws Exception {
        mockMvc.perform(post("/api/simulation/step/10000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tick").value(500));
    }

    @Test
    void resetRestartsAtTickZero() throws Exception {
        mockMvc.perform(post("/api/simulation/step/3")).andExpect(status().isOk());

        mockMvc.perform(post("/api/simulation/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tick").value(0))
                .andExpect(jsonPath("$.day").value(1))
                .andExpect(jsonPath("$.timeLabel").value("00:00"));
    }

    @Test
    void getConfigReturnsCurrentConfiguration() throws Exception {
        mockMvc.perform(get("/api/simulation/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.houseCount").exists())
                .andExpect(jsonPath("$.stepMinutes").exists())
                .andExpect(jsonPath("$.seed").exists());
    }

    @Test
    void postConfigUpdatesAndRegeneratesNeighbourhood() throws Exception {
        mockMvc.perform(post("/api/simulation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"houseCount\": 8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.houses.length()").value(8))
                .andExpect(jsonPath("$.tick").value(0)); // regenerating also resets time

        mockMvc.perform(get("/api/simulation/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.houseCount").value(8));
    }

    @Test
    void postConfigWithFixedSeedIsEchoedBack() throws Exception {
        mockMvc.perform(post("/api/simulation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seed\": 4242}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/simulation/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(4242));
    }

    @Test
    void postConfigWithStartDateAndTimeIsReflectedInState() throws Exception {
        mockMvc.perform(post("/api/simulation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\": \"2026-06-15\", \"startTime\": \"08:30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulatedDate").value("2026-06-15"))
                .andExpect(jsonPath("$.timeLabel").value("08:30"))
                .andExpect(jsonPath("$.day").value(1));

        mockMvc.perform(get("/api/simulation/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-06-15"))
                .andExpect(jsonPath("$.startTime").value("08:30"));
    }

    @Test
    void postConfigWithInvalidTypeReturnsBadRequestWithErrorBody() throws Exception {
        mockMvc.perform(post("/api/simulation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"houseCount\": \"not-a-number\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void unrecognizedConfigFieldsAreIgnoredRatherThanRejected() throws Exception {
        mockMvc.perform(post("/api/simulation/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"_comment\": \"documentation only\", \"houseCount\": 6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.houses.length()").value(6));
    }
}
