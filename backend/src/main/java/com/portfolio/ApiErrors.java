package com.portfolio;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(PortfolioService.Rejected.class)
    ResponseEntity<Map<String,String>> rejected(PortfolioService.Rejected error) {
        return ResponseEntity.status(error.status).body(Map.of("code",error.code));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    ResponseEntity<Map<String,String>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT"));
    }
}
