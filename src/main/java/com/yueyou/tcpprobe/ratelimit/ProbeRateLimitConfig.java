package com.yueyou.tcpprobe.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class ProbeRateLimitConfig implements WebMvcConfigurer {

    private final ProbeRateLimiter limiter;
    private final ObjectMapper objectMapper;

    public ProbeRateLimitConfig(ProbeRateLimiter limiter, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws IOException {
                if (!HttpMethod.POST.matches(request.getMethod()) || limiter.tryAcquire()) {
                    return true;
                }

                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader(HttpHeaders.RETRY_AFTER, "1");
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                Map<String, String> error = new LinkedHashMap<>();
                error.put("code", "rate_limit_exceeded");
                error.put("message", "Too many probe requests. Try again later.");
                objectMapper.writeValue(response.getOutputStream(), Collections.singletonMap("error", error));
                return false;
            }
        }).addPathPatterns("/probe", "/probe/");
    }
}
