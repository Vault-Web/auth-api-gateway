package vaultweb.apigateway.service.auth;

import org.springframework.stereotype.Service;

import vaultweb.apigateway.dto.request.ChangePasswordRequest;
import vaultweb.apigateway.dto.request.ChangeUsernameRequest;
import vaultweb.apigateway.dto.request.LoginRequest;
import vaultweb.apigateway.dto.request.UserRegistrationRequest;
import vaultweb.apigateway.dto.response.AuthResponse;
import vaultweb.apigateway.dto.response.UserDetails;
import vaultweb.apigateway.exceptions.DefaultException;
import vaultweb.apigateway.exceptions.dto.DefaultExceptionLevels;
import vaultweb.apigateway.model.User;
import vaultweb.apigateway.repositories.RefreshTokenRepository;
import vaultweb.apigateway.repositories.UserRepository;
import vaultweb.apigateway.util.BcryptUtil;
import vaultweb.apigateway.util.JwtUtil;
import vaultweb.apigateway.util.SecurityContextUtil;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AuthService {
  private final UserRepository userRepository;
  private final RefreshTokenService refreshTokenService;
  private final JwtUtil jwtUtil;
  private final SecurityContextUtil securityContextUtil;
  private final RefreshTokenRepository refreshTokenRepository;

  /**
   * Registers a new user based on the provided registration request.
   *
   * @param request The user registration request containing email, name, and password.
   * @return UserDetails of the newly registered user.
   * @throws RuntimeException if a user with the given email already exists.
   */
  public Mono<UserDetails> registerUser(UserRegistrationRequest request) {
    return userRepository
        .existsByEmail(request.email())
        .flatMap(
            emailExists -> {
              if (emailExists) {
                return Mono.error(
                    new DefaultException("User with email " + request.email() + " already exists"));
              }
              return userRepository.existsByUsername(request.username());
            })
        .flatMap(
            usernameExists -> {
              if (usernameExists) {
                return Mono.error(
                    new DefaultException(
                        "User with username " + request.username() + " already exists"));
              }
              return userRepository.save(
                  User.builder()
                      .email(request.email())
                      .name(request.name())
                      .username(request.username())
                      .password(BcryptUtil.encode(request.password()))
                      .build());
            })
        .map(
            user ->
                UserDetails.builder()
                    .email(user.getEmail())
                    .name(user.getName())
                    .username(user.getUsername())
                    .build());
  }

  /**
   * Authenticates a user based on the provided login request.
   *
   * @param request The login request containing email and password.
   * @return AuthResponse containing access and refresh tokens.
   */
  public Mono<AuthResponse> login(LoginRequest request) {
    return userRepository
        .findByEmailOrUsername(request.emailUsername(), request.emailUsername())
        .switchIfEmpty(
            Mono.error(
                new DefaultException(
                    "Invalid email or password", DefaultExceptionLevels.AUTHENTICATION_EXCEPTION)))
        .flatMap(
            user -> {
              // Validate password
              if (!BcryptUtil.matches(request.password(), user.getPassword())) {
                return Mono.error(
                    new DefaultException(
                        "Invalid email or password",
                        DefaultExceptionLevels.AUTHENTICATION_EXCEPTION));
              }

              // Generate tokens
              String accessToken = jwtUtil.generateToken(user.getUsername());
              return refreshTokenService
                  .createRefreshToken(user)
                  .map(
                      refreshToken ->
                          AuthResponse.builder()
                              .accessToken(accessToken)
                              .refreshToken(refreshToken.getToken())
                              .build());
            });
  }

  public Mono<Void> changePassword(ChangePasswordRequest request) {
    return securityContextUtil
        .getAuthenticatedUsername()
        .flatMap(username -> userRepository.findByUsername(username))
        .switchIfEmpty(
            Mono.error(
                new DefaultException(
                    "username from token has no registered user",
                    DefaultExceptionLevels.AUTHENTICATION_EXCEPTION)))
        .flatMap(
            user -> {
              if (!BcryptUtil.matches(request.oldPassword(), user.getPassword())) {
                return Mono.error(
                    new DefaultException(
                        "Invalid current password",
                        DefaultExceptionLevels.AUTHENTICATION_EXCEPTION));
              }
              user.setPassword(BcryptUtil.encode(request.newPassword()));
              return userRepository.save(user).then();
            });
  }

  public Mono<Void> changeUsername(ChangeUsernameRequest request) {
    return securityContextUtil
        .getAuthenticatedUsername()
        .flatMap(userRepository::findByUsername)
        .switchIfEmpty(
            Mono.error(
                new DefaultException(
                    "username from token has no registered user",
                    DefaultExceptionLevels.AUTHENTICATION_EXCEPTION)))
        .flatMap(
            user -> {
              if (request.newUsername().equals(user.getUsername())) {
                return Mono.error(
                    new DefaultException(
                        "You didn't change anything",
                        DefaultExceptionLevels.DEFAULT_EXCEPTION));
              }

              return userRepository
                  .existsByUsername(request.newUsername())
                  .flatMap(
                      usernameExists -> {
                        if (usernameExists) {
                          return Mono.error(
                              new DefaultException(
                                  "User with username "
                                      + request.newUsername()
                                      + " already exists"));
                        }

                        user.setUsername(request.newUsername());
                        return userRepository.save(user).then();
                      });
            });
  }

  public Mono<AuthResponse> switchToken(String token) {
    // find refresh
    return refreshTokenRepository
        .findByToken(token)
        .switchIfEmpty(
            Mono.error(
                new DefaultException(
                    "Refresh token not found", DefaultExceptionLevels.AUTHENTICATION_EXCEPTION)))
        .flatMap(refreshTokenService::verifyExpiration)
        .flatMap(
            refreshToken ->
                userRepository
                    .findById(refreshToken.getUserId())
                    .switchIfEmpty(
                        Mono.error(
                            new DefaultException(
                                "Refresh token not found",
                                DefaultExceptionLevels.AUTHENTICATION_EXCEPTION))))
        .flatMap(
            user -> {
              // generate new jwt
              String accessToken = jwtUtil.generateToken(user.getUsername());
              // generate new refresh token
              return refreshTokenService
                  .createRefreshToken(user)
                  .flatMap(
                      refreshToken -> {
                        // new refresh token
                        return Mono.just(new AuthResponse(accessToken, refreshToken.getToken()));
                      });
            });
  }

  // get user-details
  public Mono<UserDetails> getUserDetails() {
    return securityContextUtil
        .getAuthenticatedUsername()
        .flatMap(
            username ->
                userRepository
                    .findByUsername(username)
                    .switchIfEmpty(
                        Mono.error(
                            new DefaultException(
                                "username from token has no registered user",
                                DefaultExceptionLevels.AUTHENTICATION_EXCEPTION)))
                    .map(
                        user ->
                            UserDetails.builder()
                                .email(user.getEmail())
                                .name(user.getName())
                                .username(user.getUsername())
                                .build()));
  }

  /**
   * Logs out the currently authenticated user by deleting all refresh tokens associated with the
   * user identified in the security context.
   *
   * <p>If no user can be resolved from the current authentication context, a {@link
   * DefaultException} with level {@link DefaultExceptionLevels#AUTHENTICATION_EXCEPTION} is
   * emitted.
   *
   * @return a {@link Mono} that completes when the user's refresh tokens have been deleted
   */
  public Mono<Void> logout() {
    return securityContextUtil
        .getAuthenticatedUsername()
        .flatMap(
            username ->
                userRepository
                    .findByUsername(username)
                    .switchIfEmpty(
                        Mono.error(
                            new DefaultException(
                                "username from token has no registered user",
                                DefaultExceptionLevels.AUTHENTICATION_EXCEPTION))))
        .flatMap(user -> refreshTokenRepository.deleteByUserId(user.getId()));
  }
}
