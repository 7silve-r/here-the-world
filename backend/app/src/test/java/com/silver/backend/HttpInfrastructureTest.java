package com.silver.backend;

import com.silver.backend.bootstrap.GlobalExceptionHandler;
import com.silver.backend.bootstrap.HttpErrors;
import com.silver.backend.bootstrap.RequestIdFilter;
import com.silver.backend.business.HttpReplies;
import com.silver.backend.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpInfrastructureTest extends BackendTestSupport {
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private GlobalExceptionHandler advice;
    @Autowired private HttpErrors httpErrors;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(advice).addFilters(requestIdFilter).build();
    }

    @Test
    void keepsRequestId() throws Exception {
        mvc.perform(get("/test/ok").header("X-Request-Id", "client-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "client-123"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.requestId").value("client-123"))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @Test
    void replacesInvalidId() throws Exception {
        var result = mvc.perform(get("/test/ok").header("X-Request-Id", "invalid id"))
                .andExpect(status().isOk()).andReturn();
        String id = result.getResponse().getHeader("X-Request-Id");
        assertEquals(id, UUID.fromString(id).toString());
        assertEquals(id, result.getRequest().getAttribute("requestId"));
    }

    @Test
    void returnsValidationDetails() throws Exception {
        mvc.perform(get("/test/invalid").header("X-Request-Id", "validation-1"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.requestId").value("validation-1"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("email"))
                .andExpect(jsonPath("$.details[0].reason").value("INVALID_FORMAT"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"broken\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void writesRetryAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("requestId", "rate-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ApiException error = new ApiException(429, "RATE_LIMITED", "请求过于频繁", List.of(), 30);

        httpErrors.write(request, response, error);

        assertEquals(429, response.getStatus());
        assertEquals("30", response.getHeader("Retry-After"));
        assertEquals("30", httpErrors.response(request, error).getHeaders().getFirst("Retry-After"));
        assertTrue(response.getContentAsString().contains("请求过于频繁"));
    }

    @TestComponent
    @RestController
    static class ProbeController {
        @GetMapping("/test/ok")
        ResponseEntity<?> ok(HttpServletRequest request) {
            return HttpReplies.ok(request, "ok");
        }

        @GetMapping("/test/invalid")
        void invalid() {
            throw ApiException.invalid("email", "INVALID_FORMAT", "请输入有效邮箱");
        }

        @PostMapping("/test/body")
        Map<String, Object> body(@RequestBody Map<String, Object> request) {
            return request;
        }
    }
}
