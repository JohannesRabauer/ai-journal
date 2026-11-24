package dev.rabauer.ai.diary.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequestGuardFiltersTest {

    @RestController
    static class Probe {
        @GetMapping({"/upload/x", "/other"})
        String read() {
            return "ok";
        }

        @PostMapping({"/upload/x", "/other"})
        String write() {
            return "ok";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .addFilters(new HostAllowlistFilter(List.of("pi.local", " raspberrypi ")), new UploadGuardFilter(),
                        new SecurityHeadersFilter())
                .build();
    }

    @Test
    void foreignHostIsRejectedWithoutDetails() throws Exception {
        mvc.perform(get("/other").header("Host", "attacker.example")).andExpect(status().is(421));
        mvc.perform(get("/other").header("Host", "attacker.example:8081")).andExpect(status().is(421));
    }

    @Test
    void loopbackAndConfiguredHostsAreAccepted() throws Exception {
        for (String host : new String[]{"127.0.0.1:8081", "localhost", "localhost:8081", "[::1]:8081", "pi.local:8081", "RaspberryPi"}) {
            mvc.perform(get("/other").header("Host", host)).andExpect(status().isOk());
        }
    }

    @Test
    void uploadWritesNeedTheHeader() throws Exception {
        mvc.perform(post("/upload/x").header("Host", "localhost:8081").contentType("text/plain").content("x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_HEADER_REQUIRED"));
        mvc.perform(post("/upload/x").header("Host", "localhost:8081").content("x"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/upload/x").header("Host", "localhost:8081").header("X-AI-Diary", "1").content("x"))
                .andExpect(status().isOk());
        mvc.perform(get("/upload/x").header("Host", "localhost:8081")).andExpect(status().isOk());
    }

    @Test
    void foreignOriginAndCrossSiteAreRejectedOwnOriginPasses() throws Exception {
        mvc.perform(post("/upload/x").header("Host", "localhost:8081").header("X-AI-Diary", "1")
                .header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/upload/x").header("Host", "localhost:8081").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/upload/x").header("Host", "localhost:8081").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/upload/x").header("Host", "localhost:8081").header("X-AI-Diary", "1")
                .header("Origin", "http://localhost:8081")).andExpect(status().isOk());
    }

    @Test
    void headerRuleAppliesToUploadOnly() throws Exception {
        // Vaadin's own requests carry Vaadin's CSRF token; this filter must not require the header there.
        mvc.perform(post("/other").header("Host", "localhost:8081").content("x")).andExpect(status().isOk());
    }

    @Test
    void antiFramingHeadersOnEveryResponse() throws Exception {
        mvc.perform(get("/other").header("Host", "localhost")).andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
