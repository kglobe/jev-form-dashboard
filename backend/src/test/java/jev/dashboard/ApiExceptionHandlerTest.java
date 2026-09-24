package jev.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ApiExceptionHandlerTest {
    @Autowired MockMvc mockMvc;

    @Test
    void missingRequestHeaderReturnsErrorBody() throws Exception {
        mockMvc.perform(get("/api/test/echo-user"))
            .andExpect(status().is4xxClientError())
            .andExpect(jsonPath("$.error").exists());
    }

    @RestController
    static class TestOnlyController {
        @GetMapping("/api/test/echo-user")
        String echo(@RequestHeader("X-User-Id") String userId) {
            return userId;
        }
    }
}
