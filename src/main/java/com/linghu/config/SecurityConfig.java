package com.linghu.config;

import com.linghu.filter.JwtAuthenticationFilter;
import com.linghu.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Collections;

/**
 * Spring Security 配置
 * 主要用于：1.BCrypt密码加密  2.放行白名单  3.JWT过滤器
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;

    /**
     * BCrypt 密码加密器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 安全过滤链配置
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（使用JWT无状态）
            .csrf().disable()
            // 启用 CORS
            .cors().configurationSource(corsConfigurationSource())
            .and()
            // 无状态 Session
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            // 权限配置
            .authorizeRequests()
                // 放行认证接口
                .antMatchers("/api/auth/**").permitAll()
                // 放行商品图片静态资源（无需登录可访问）
                .antMatchers("/uploads/**").permitAll()
                // 放行Mock接口（开发调试用）
                .antMatchers("/api/mock/**").permitAll()
                // 放行WebSocket端点
                .antMatchers("/ws/**").permitAll()
                // 放行Swagger
                .antMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // 放行商品浏览接口（游客可访问）
                .antMatchers("/api/consumer/products/nearby", "/api/consumer/product/detail/**", "/api/consumer/warehouses/map").permitAll()
                // 放行拉新活动公开接口（活动列表、详情、落地页均无需登录）
                .antMatchers(
                    "/api/consumer/activity/list",
                    "/api/consumer/activity/*/detail",
                    "/api/consumer/activity/by-invite-code"
                ).permitAll()
                // 放行支付回调端点（支付宝/微信主动回调，无 JWT）
                .antMatchers("/api/payment/alipay/notify", "/api/payment/wechat/notify").permitAll()
                // 其余接口需要认证
                .anyRequest().authenticated()
            .and()
            // 异常处理（未登录返回401）
            .exceptionHandling()
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("{\"code\":401,\"msg\":\"请先登录\",\"data\":null}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.getWriter().write("{\"code\":403,\"msg\":\"权限不足\",\"data\":null}");
                })
            .and()
            // 添加 JWT 过滤器（在用户名密码过滤器之前）
            .addFilterBefore(new JwtAuthenticationFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS 跨域配置（开发环境允许所有来源）
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Collections.singletonList("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Collections.singletonList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
