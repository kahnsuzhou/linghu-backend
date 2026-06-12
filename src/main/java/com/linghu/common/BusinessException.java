package com.linghu.common;

import lombok.Getter;

/**
 * 业务异常
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = R.ERROR_CODE;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
