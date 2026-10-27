package com.dbappsecurity.prd2api.config;

import com.dbappsecurity.prd2api.util.TenantContextUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthInterceptorTest {

    private Prd2ApiProperties properties;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new Prd2ApiProperties();
        interceptor = new AuthInterceptor(new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() {
        TenantContextUtil.clear();
    }

    @Test
    void validHeaderShouldPassAndBindContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/task/page");
        request.addHeader("X-Tenant-Id", "tenant_01");
        request.addHeader("X-User-Key", "user@corp.com");
        request.addHeader("X-User-Role", "ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertTrue("tenant_01".equals(TenantContextUtil.getTenantId()));
        assertTrue(TenantContextUtil.isAdmin());
        assertTrue(response.getHeader("X-Trace-Id") != null);
    }

    @Test
    void invalidTenantShouldBeRejectedToPreventPathTraversal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/file/upload");
        request.addHeader("X-Tenant-Id", "../../etc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertTrue(response.getContentAsString().contains("\"code\":400"));
    }

    @Test
    void adminApiShouldRequireAdminRole() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/stats");
        request.addHeader("X-Tenant-Id", "tenant_01");
        request.addHeader("X-User-Role", "USER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertTrue(response.getContentAsString().contains("\"code\":403"));
    }

    @Test
    void unknownRoleShouldBeDowngradedToUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/task/page");
        request.addHeader("X-Tenant-Id", "tenant_01");
        request.addHeader("X-User-Role", "SUPER_ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertFalse(TenantContextUtil.isAdmin());
    }

    @Test
    void requireIdentityShouldRejectMissingHeaders() throws Exception {
        properties.getSecurity().setRequireIdentity(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/task/page");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertTrue(response.getContentAsString().contains("\"code\":401"));
    }
}
