package com.jasonlat.ai.test.config;

import com.jasonlat.ai.config.WebCorsConfiguration;
import com.jasonlat.ai.trigger.http.HealthController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 Spring MVC + 全局 CORS Filter，不启动数据库、模型服务或 SSH 连接。 */
class WebCorsConfigurationTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void start() {
        createMvc(null);
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    private void createMvc(String origins) {
        if (context != null) {
            context.close();
        }
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        if (origins != null) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("cors-test", Map.of("app.config.cross-origin", origins)));
        }
        context.register(WebCorsConfiguration.class, TestMvcConfiguration.class);
        context.refresh();
        var filter = context.getBean(WebCorsConfiguration.class).loopbackCorsFilter().getFilter();
        assertNotNull(filter);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(filter, "/*")
                .defaultRequest(get("/").secure(true).with(request -> {
                    request.setServerName("backend.example.test");
                    request.setServerPort(443);
                    return request;
                }))
                .build();
    }

    @Test
    void allowsHttpAndHttpsLoopbackWithDefaultOrExplicitPorts() throws Exception {
        for (String origin : new String[]{"http://127.0.0.1", "https://127.0.0.1",
                "http://127.0.0.1:4321", "https://127.0.0.1:65535"}) {
            mvc.perform(get("/api/health").accept(MediaType.APPLICATION_JSON).header("Origin", origin))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
                    .andExpect(jsonPath("$.status").value("UP"));
        }
    }

    @Test
    void rejectsOtherHostsTauriNullAndLookalikeOrigins() throws Exception {
        for (String origin : new String[]{"https://outside.example", "http://localhost:4321",
                "http://tauri.localhost", "tauri://localhost", "null", "https://[::1]:4321",
                "http://127.0.0.2:4321", "http://127.0.0.1.evil.example:4321",
                "http://127.0.0.1@evil.example:4321"}) {
            mvc.perform(post("/api/v1/files/probe").header("Origin", origin))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
        assertEquals(0, context.getBean(ProbeController.class).calls.get(),
                "拒绝的跨域请求不能执行 Controller");
    }

    @Test
    void rejectsDisallowedOriginEvenWhenItMatchesTheRequestHost() throws Exception {
        mvc.perform(get("/api/health").secure(true).with(request -> {
                    request.setServerName("localhost");
                    request.setServerPort(8889);
                    return request;
                }).header("Origin", "https://localhost:8889"))
                .andExpect(status().isForbidden());
    }

    @Test
    void appliesToEveryRouteAndCannotBeBroadenedByControllerAnnotation() throws Exception {
        for (String path : new String[]{"/api/health", "/agent/probe", "/api/v1/ssh/probe",
                "/api/v1/sftp/probe", "/api/v1/files/probe", "/api/v1/files/local/probe"}) {
            mvc.perform(get(path).header("Origin", "http://127.0.0.1:4321"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:4321"));
            mvc.perform(get(path).header("Origin", "https://outside.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void handlesPreflightBeforeTheControllerAndPreservesApiMethods() throws Exception {
        mvc.perform(options("/api/v1/sftp/probe")
                        .header("Origin", "https://127.0.0.1:4321")
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", "Content-Type,Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://127.0.0.1:4321"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("DELETE")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("Authorization")));
        mvc.perform(options("/api/v1/sftp/probe")
                        .header("Origin", "https://outside.example")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/v1/sftp/probe")
                        .header("Origin", "https://127.0.0.1:4321")
                        .header("Access-Control-Request-Method", "TRACE"))
                .andExpect(status().isForbidden());
        assertEquals(0, context.getBean(ProbeController.class).calls.get());
    }

    @Test
    void leavesNativeRequestsWithoutOriginUnaffected() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(post("/api/v1/sftp/probe"))
                .andExpect(status().isOk());
        assertEquals(1, context.getBean(ProbeController.class).calls.get());
    }

    @Test
    void readsConfiguredOriginListAndCanNarrowAllowedPorts() throws Exception {
        createMvc(" http://127.0.0.1:4321 , https://127.0.0.1:4322 ");
        mvc.perform(get("/api/health").header("Origin", "http://127.0.0.1:4321"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/health").header("Origin", "https://127.0.0.1:4322"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/health").header("Origin", "http://127.0.0.1:4322"))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesConfigurationThatWidensTheOriginPolicy() {
        for (String origins : new String[]{"*", "", "http://localhost", "http://tauri.localhost,tauri://localhost",
                "https://outside.example", "http://127.0.0.1,https://outside.example",
                "http://127.0.0.1/", "http://127.0.0.1:0", "http://127.0.0.1:65536",
                "http://127.0.0.1:999999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> new WebCorsConfiguration(origins), origins);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class TestMvcConfiguration {
        @Bean
        HealthController healthController() {
            return new HealthController();
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    /** 故意保留宽泛注解，验证全局 Filter 不会被局部规则放宽。 */
    @RestController
    @CrossOrigin("*")
    static class ProbeController {
        final AtomicInteger calls = new AtomicInteger();

        @RequestMapping({"/agent/probe", "/api/v1/ssh/probe", "/api/v1/sftp/probe",
                "/api/v1/files/probe", "/api/v1/files/local/probe"})
        String probe() {
            calls.incrementAndGet();
            return "ok";
        }
    }
}
