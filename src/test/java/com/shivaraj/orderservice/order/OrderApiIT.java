package com.shivaraj.orderservice.order;

import com.shivaraj.orderservice.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end API test against a real PostgreSQL (Testcontainers):
 * HTTP -> controller -> service -> JPA -> Flyway-managed schema.
 * Runs in `mvn verify` (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false) // keep Prometheus export on in tests; no trace export
@Import(TestcontainersConfig.class)
class OrderApiIT {

    @Autowired
    MockMvc mvc;

    private static final String NEW_ORDER = """
            {
              "customerId": "cust-42",
              "items": [
                {"productCode": "SKU-1", "quantity": 2, "unitPrice": 19.99},
                {"productCode": "SKU-2", "quantity": 1, "unitPrice": 5.00}
              ]
            }
            """;

    private String createOrder() throws Exception {
        String body = mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(NEW_ORDER))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/orders/")))
                .andExpect(jsonPath("$.totalAmount").value(44.98))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }

    @Test
    void createThenFetchOrder() throws Exception {
        String id = createOrder();

        mvc.perform(get("/api/v1/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.customerId").value("cust-42"));
    }

    @Test
    void listFiltersByCustomer() throws Exception {
        createOrder();

        mvc.perform(get("/api/v1/orders").param("customerId", "cust-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", not(empty())))
                .andExpect(jsonPath("$.content[*].customerId", everyItem(is("cust-42"))));
    }

    @Test
    void statusLifecycleAndInvalidTransition() throws Exception {
        String id = createOrder();

        mvc.perform(patch("/api/v1/orders/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mvc.perform(patch("/api/v1/orders/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CREATED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Invalid status transition"));
    }

    @Test
    void validationErrorsReturnProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.customerId").exists())
                .andExpect(jsonPath("$.errors.items").exists());
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        mvc.perform(get("/api/v1/orders/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void prometheusEndpointExposesMetrics() throws Exception {
        createOrder();

        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("orders_placed_total")))
                .andExpect(content().string(containsString("http_server_requests_seconds")));
    }
}
