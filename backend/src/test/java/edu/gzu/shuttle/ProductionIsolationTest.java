package edu.gzu.shuttle;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo", "prod"})
class ProductionIsolationTest {
  @Autowired ApplicationContext context;
  @Autowired MockMvc mvc;

  @Test
  void productionNeverRegistersDemoInitializerOrResetEndpoint() throws Exception {
    assertThat(context.getBeansOfType(DemoService.class)).isEmpty();
    mvc.perform(
            post("/api/admin/demo/reset")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content("{\"confirmation\":\"RESET DEMO\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(get("/api/admin/overview").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.demoResetEnabled").value(false));
  }

  @Test
  void missingQueryParameterHasControlledClientError() throws Exception {
    mvc.perform(get("/api/passenger/eta").with(user("passenger").roles("PASSENGER")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
