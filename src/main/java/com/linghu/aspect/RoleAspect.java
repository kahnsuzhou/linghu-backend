package com.linghu.aspect;

import com.linghu.annotation.RequireRole;
import com.linghu.common.BusinessException;
import com.linghu.common.R;
import com.linghu.util.SecurityUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 角色权限控制切面
 */
@Slf4j
@Aspect
@Component
public class RoleAspect {

    @Around("@annotation(requireRole)")
    public Object checkRole(ProceedingJoinPoint joinPoint, RequireRole requireRole) throws Throwable {
        Integer currentRole = SecurityUtil.getCurrentRole();
        if (currentRole == null) {
            throw new BusinessException(R.UNAUTHORIZED_CODE, "请先登录");
        }

        int[] allowedRoles = requireRole.value();
        boolean hasRole = false;
        for (int role : allowedRoles) {
            if (role == currentRole) {
                hasRole = true;
                break;
            }
        }

        if (!hasRole) {
            log.warn("角色权限不足: 当前角色={}, 需要角色={}", currentRole, Arrays.toString(allowedRoles));
            throw new BusinessException(R.FORBIDDEN_CODE, "权限不足，无法访问");
        }

        return joinPoint.proceed();
    }
}
