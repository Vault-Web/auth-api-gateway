package vaultweb.apigateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vaultweb.apigateway.dto.request.ChangePasswordRequest;
import vaultweb.apigateway.dto.request.ChangeUsernameRequest;
import vaultweb.apigateway.dto.request.LoginRequest;
import vaultweb.apigateway.dto.request.UserRegistrationRequest;
import vaultweb.apigateway.dto.response.AuthResponse;
import vaultweb.apigateway.dto.response.UserDetails;
import vaultweb.apigateway.service.auth.AuthService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@ResponseBody
public class GatewayAuthController {
  private final AuthService authService;

  @ResponseStatus(HttpStatus.OK)
  @GetMapping("/me")
  public Mono<UserDetails> getMyData() {
    return authService.getUserDetails();
  }

  @ResponseStatus(HttpStatus.OK)
  @PostMapping("/login")
  public Mono<AuthResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
    return authService.login(loginRequest);
  }

  @ResponseStatus(HttpStatus.CREATED)
  @PostMapping("/register")
  public Mono<UserDetails> register(@Valid @RequestBody UserRegistrationRequest request) {
    return authService.registerUser(request);
  }

  @ResponseStatus(HttpStatus.OK)
  @GetMapping("/refresh/{token}")
  public Mono<AuthResponse> refreshToken(@PathVariable @Valid @NotEmpty String token) {
    return authService.switchToken(token);
  }

  @ResponseStatus(HttpStatus.OK)
  @DeleteMapping("/logout")
  public Mono<Void> logout() {
    return authService.logout();
  }

  @ResponseStatus(HttpStatus.OK)
  @PostMapping("change-username")
  public Mono<Void> changeUsername(@Valid @RequestBody ChangeUsernameRequest request){
    return authService.changeUsername(request);
  }

  @PostMapping("change-email")
  public String changeEmail() {
    return "changeEmail";
  }

  @ResponseStatus(HttpStatus.OK)
  @PostMapping("change-password")
  public Mono<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
    return authService.changePassword(request);
  }

  @PostMapping("/reset-password")
  public String resetPassword() {
    return "resetPassword";
  }

  @PostMapping("/verify-email")
  public String verifyEmail() {
    return "verifyEmail";
  }
}
