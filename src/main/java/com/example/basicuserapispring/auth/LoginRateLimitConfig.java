package com.example.basicuserapispring.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class LoginRateLimitConfig {
    @Bean
    LoginRateLimiter loginRateLimiter(
            @Value("${security.login.ip-limit}") int ipLimit,
            @Value("${security.login.account-limit}") int accountLimit,
            @Value("${security.login.max-entries}") int maxEntries,
            @Value("${security.login.window}") Duration window) {
        return new LoginRateLimiter(ipLimit, accountLimit, maxEntries, window, Clock.systemUTC());
    }

    @Bean
    WebMvcConfigurer loginRateLimitInterceptor(LoginRateLimiter limiter) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        if ("POST".equals(request.getMethod())) limiter.checkIp(request.getRemoteAddr());
                        return true;
                    }
                }).addPathPatterns("/api/login");
            }
        };
    }
}
