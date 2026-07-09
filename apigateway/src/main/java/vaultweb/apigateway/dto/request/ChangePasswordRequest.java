package vaultweb.apigateway.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

public record ChangePasswordRequest(
    @NotEmpty(message = "Your old password logic that it cannot be empty") String oldPassword,
    @NotEmpty(message = "Your new password logic that it cannot be empty")
        @Pattern(
            regexp = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=])(?=\\S+$).{8,}$",
            message =
                "password must be at least 8 characters long and include at least one uppercase letter, one lowercase letter, one digit, and one special character")
        String newPassword) {}
