package com.zack.linerelay.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LineSecurityFilterTest {

    @Test
    void adminPathFailsClosedWhenNoAdminKeyIsConfigured() throws Exception {
        LineSecurityFilter filter = new LineSecurityFilter(new LineSecurityProperties(), new InMemoryRateLimiter());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/list-targets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("admin_security_not_configured");
    }

    @Test
    void adminPathRejectsMissingOrInvalidKey() throws Exception {
        LineSecurityProperties properties = new LineSecurityProperties();
        properties.setAdminApiKeys("admin-key");
        LineSecurityFilter filter = new LineSecurityFilter(properties, new InMemoryRateLimiter());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/poll-market-analysis");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("invalid_admin_api_key");
    }

    @Test
    void adminPathAllowsConfiguredKey() throws Exception {
        LineSecurityProperties properties = new LineSecurityProperties();
        properties.setAdminApiKeys("admin-key");
        LineSecurityFilter filter = new LineSecurityFilter(properties, new InMemoryRateLimiter());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/list-targets");
        request.addHeader("X-Line-Admin-Key", "admin-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void webhookPostIsRateLimitedBeforeControllerWork() throws Exception {
        LineSecurityProperties properties = new LineSecurityProperties();
        properties.setAdminApiKeys("admin-key");
        properties.setWebhookRateLimitPerMinute(1);
        LineSecurityFilter filter = new LineSecurityFilter(properties, new InMemoryRateLimiter());

        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/webhook");
        first.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, new MockFilterChain());

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/webhook");
        second.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getContentAsString()).contains("webhook_rate_limited");
    }
}
