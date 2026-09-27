package com.mine.dispatch.web;

import com.mine.dispatch.capability.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<Map<String,String>> denied(AccessDeniedException e){return ResponseEntity.status(e.status()).body(Map.of("error",e.code(),"message",e.getMessage()));}
    @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<Map<String,String>> invalid(MethodArgumentNotValidException e){return ResponseEntity.badRequest().body(Map.of("error","invalid_request","message","请求字段不合法"));}
}
