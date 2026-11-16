package com.gzu.shuttle.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {
    private final SessionAuthInterceptor interceptor;
    private final ApiRateLimitInterceptor rateLimitInterceptor;
    public AuthWebConfig(SessionAuthInterceptor interceptor, ApiRateLimitInterceptor rateLimitInterceptor) {
        this.interceptor = interceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**").order(-1);
    }
}
