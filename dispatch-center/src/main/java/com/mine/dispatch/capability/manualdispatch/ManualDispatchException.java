package com.mine.dispatch.capability.manualdispatch;

import org.springframework.http.HttpStatus;

public class ManualDispatchException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ManualDispatchException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
