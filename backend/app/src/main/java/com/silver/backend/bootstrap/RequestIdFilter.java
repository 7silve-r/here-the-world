package com.silver.backend.bootstrap;

import com.silver.backend.common.Values;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String id = request.getHeader("X-Request-ID");
        if(id == null || !id.matches("[a-zA-Z0-9_-]{1,100}")) {
            id = Values.id();
        }
        request.setAttribute("requestId", id);
        response.setHeader("X-Request-ID", id);
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        filterChain.doFilter(request,response);
    }
}
