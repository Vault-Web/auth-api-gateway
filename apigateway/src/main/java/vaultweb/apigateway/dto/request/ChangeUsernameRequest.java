package vaultweb.apigateway.dto.request;

import jakarta.validation.constraints.NotEmpty;

public record ChangeUsernameRequest(
    @NotEmpty(message = "Your new username is required") String newUsername) {}
