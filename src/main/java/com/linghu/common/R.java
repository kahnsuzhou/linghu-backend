package com.linghu.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结果封装
 */
@Data
public class R<T> implements Serializable {

    private Integer code;
    private String msg;
    private T data;

    // 成功状态码
    public static final int SUCCESS_CODE = 200;
    // 失败状态码
    public static final int ERROR_CODE = 500;
    // 未授权
    public static final int UNAUTHORIZED_CODE = 401;
    // 禁止访问
    public static final int FORBIDDEN_CODE = 403;

    private R() {}

    private R(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> R<T> ok() {
        return new R<>(SUCCESS_CODE, "操作成功", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS_CODE, "操作成功", data);
    }

    public static <T> R<T> ok(String msg, T data) {
        return new R<>(SUCCESS_CODE, msg, data);
    }

    public static <T> R<T> fail(String msg) {
        return new R<>(ERROR_CODE, msg, null);
    }

    public static <T> R<T> fail(String msg, T data) {
        return new R<>(ERROR_CODE, msg, data);
    }

    public static <T> R<T> fail(Integer code, String msg) {
        return new R<>(code, msg, null);
    }

    public static <T> R<T> unauthorized(String msg) {
        return new R<>(UNAUTHORIZED_CODE, msg, null);
    }

    public static <T> R<T> forbidden(String msg) {
        return new R<>(FORBIDDEN_CODE, msg, null);
    }

    public boolean isSuccess() {
        return this.code != null && this.code == SUCCESS_CODE;
    }
}
