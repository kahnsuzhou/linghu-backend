package com.linghu.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linghu.entity.User;
import com.linghu.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 认证过滤器
 * 从 Authorization Header 中提取 token，验证后设置 SecurityContext
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (StringUtils.hasText(token)) {
            try {
                Claims claims = jwtUtil.parseToken(token);
                Long userId = Long.valueOf(claims.get("userId").toString());
                String username = claims.getSubject();
                Integer role = (Integer) claims.get("role");

                // 构造 User 实体作为 Principal
                User user = new User();
                user.setId(userId);
                user.setUsername(username);
                user.setRole(role);

                // 设置权限（ROLE_0, ROLE_1, ROLE_2）
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        user,
                        null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
                );

                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("JWT 认证成功: userId={}, username={}, role={}", userId, username, role);

            } catch (ExpiredJwtException e) {
                log.warn("JWT Token 已过期");
                writeErrorResponse(response, 401, "Token已过期，请重新登录");
                return;
            } catch (JwtException e) {
                log.warn("JWT Token 无效: {}", e.getMessage());
                writeErrorResponse(response, 401, "Token无效，请重新登录");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 从请求头中提取 token
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    /**
     * 写入错误响应
     */
    private void writeErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(status);
        Map<String, Object> result = new HashMap<>();
        result.put("code", status);
        result.put("msg", message);
        result.put("data", null);
        response.getWriter().write(new ObjectMapper().writeValueAsString(result));
    }
}
