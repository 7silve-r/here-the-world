package com.silver.backend.bootstrap;

import com.silver.backend.business.auth.AuthController;
import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.system.RequestControl;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ApiSecurityFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(ApiSecurityFilter.class);
    private final AuthService service;
    private final Crypto crypto;
    private final HttpErrors errors;
    private final RequestControl control;
    private final Set<String> origins;

    public ApiSecurityFilter(
            AuthService service, Crypto crypto, HttpErrors errors, RequestControl requestControl, String origins) {
        this.service = service;
        this.crypto = crypto;
        this.errors = errors;
        this.control = requestControl;
        this.origins = new HashSet<>(Arrays.asList(origins.split(",")));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            Viewer viewer = null;
            if (header != null) {
                if (!header.startsWith("Bearer ") || header.length() > 9000) {
                    throw new ApiException(401, "TOKEN_INVALID", "登录凭证无效");
                }
                viewer = crypto.verifyJwt(header.substring(7));
                service.checkSession(viewer);
            } else if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                    && request.getRequestURI().startsWith("/api/v1/media/private/")) {
                String ticket = AuthController.getCookie(request, "htw_media");
                if (ticket != null) {
                    viewer = service.mediaViewer(ticket);
                }
            }
            if (viewer != null) {
                request.setAttribute("viewer", viewer);
                SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(viewer, null, List.of()));
            }
            if ("POST".equals(request.getMethod()) && request.getRequestURI().startsWith("/api/v1/auth/")) {
                String origin = request.getHeader("Origin");
                if (origin == null || !origins.contains(origin)) {
                    throw new ApiException(403, "CSRF_INVALID", "请求来源不在允许范围内");
                }
                service.verifyCsrf(
                        AuthController.getCookie(request, "htw_csrf_context"),
                        request.getHeader("X-CSRF-TOKEN"));
                control.limit("auth-source", request.getRemoteAddr(), 180, 60);
            }
            boolean binaryUpload = "/api/v1/media/upload".equals(request.getRequestURI());
            if (!binaryUpload && request.getContentLength() > 1_048_576) {
                throw new ApiException(413, "FILE_TOO_LARGE", "JSON 请求过大");
            }
            filterChain.doFilter(binaryUpload ? request : boundJson(request), response);
        } catch (ApiException error) {
            errors.write(request, response, error);
        } catch (DataAccessException error) {
            LOG.error("Security database error requestId={}, method={}, path={}, type={}",
                    request.getAttribute("requestId"), request.getMethod(), request.getRequestURI(),
                    error.getClass().getSimpleName(), error);
            errors.write(request, response, new ApiException(503, "SERVICE_UNAVAILABLE", "数据库暂时不可用"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private HttpServletRequest boundJson(HttpServletRequest request) {
        return new HttpServletRequestWrapper(request) {
            private ServletInputStream limited;

            @Override
            public ServletInputStream getInputStream() throws IOException {
                if (limited != null) {
                    return limited;
                }
                ServletInputStream original = super.getInputStream();
                limited = new ServletInputStream() {
                    private long count;

                    private void countDown(int n) {
                        if (n > 0) {
                            count += n;
                            if (count > 1_048_576) {
                                throw new ApiException(413, "FILE_TOO_LARGE", "JSON 请求过大");
                            }
                        }
                    }

                    @Override
                    public int read() throws IOException {
                        int n = original.read();
                        if (n >= 0) {
                            countDown(1);
                        }
                        return n;
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        int n = original.read(b, off, len);
                        countDown(n);
                        return n;
                    }

                    @Override
                    public boolean isFinished() {
                        return original.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return original.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        original.setReadListener(listener);
                    }
                };
                return limited;
            }

            @Override
            public BufferedReader getReader() throws IOException {
                return new BufferedReader(new InputStreamReader(getInputStream(),  StandardCharsets.UTF_8));
            }
        };
    }
}
