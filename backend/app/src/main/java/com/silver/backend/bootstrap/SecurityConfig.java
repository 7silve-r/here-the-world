package com.silver.backend.bootstrap;

import com.silver.backend.business.auth.AuthService;
import com.silver.backend.business.system.RequestControl;
import com.silver.backend.common.ApiException;
import com.silver.backend.infrastructure.Crypto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthService auth,
            Crypto crypto,
            HttpErrors errors,
            RequestControl control,
            @Value("${htw.allowed-origins}") String origins
    ) {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (req, res, f) ->
                                                        errors.write(req, res, new ApiException(401, "AUTH_REQUIRED", "请先登录")))
                                        .accessDeniedHandler(
                                                (req, res, f) ->
                                                        errors.write(req, res, new ApiException(403, "FORBIDDEN", "无权访问"))))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers("/error")
                                        .permitAll()
                                        .requestMatchers("/api/v1/auth/change-password")
                                        .authenticated()
                                        .requestMatchers("/api/v1/auth/**", "/api/v1/media/**")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/system/capabilities",
                                                "/api/v1/feed",
                                                "/api/v1/users/**",
                                                "/api/v1/works/*",
                                                "/api/v1/works/*/comments",
                                                "/api/v1/assets/*")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(
                        new ApiSecurityFilter(auth, crypto, errors, control, origins),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
