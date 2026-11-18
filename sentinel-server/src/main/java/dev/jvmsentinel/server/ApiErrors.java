package dev.jvmsentinel.server;

import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class ApiErrors {
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiModels.ApiError> safeError(Exception failure) {
        if (failure instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            if (response.getStatusCode().value() == 404) {
                return ResponseEntity.status(404).body(new ApiModels.ApiError("not_found", "Resource not found."));
            }
            return ResponseEntity.status(response.getStatusCode())
                    .body(new ApiModels.ApiError("invalid_request", "Request could not be processed."));
        }
        System.getLogger(ApiErrors.class.getName()).log(System.Logger.Level.ERROR, "Unhandled local API error", failure);
        return ResponseEntity.internalServerError()
                .body(new ApiModels.ApiError("internal_error", "The request could not be completed."));
    }
}
