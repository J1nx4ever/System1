package com.mine.dispatch.capability.access;

import org.springframework.http.HttpStatus;

public class AccessDeniedException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public AccessDeniedException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
