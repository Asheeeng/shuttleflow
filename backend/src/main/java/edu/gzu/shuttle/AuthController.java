package edu.gzu.shuttle;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.*;
import org.springframework.security.core.context.*;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
  private final UserRepo users;
  private final PasswordEncoder encoder;
  private final UserDetailsService details;
  private final SecurityContextRepository contexts;

  public AuthController(
      UserRepo u, PasswordEncoder e, UserDetailsService d, SecurityContextRepository c) {
    users = u;
    encoder = e;
    details = d;
    contexts = c;
  }

  record Login(
      @NotBlank @Size(max = 60) String username, @NotBlank @Size(max = 100) String password) {}

  @GetMapping("/health")
  Api<?> health() {
    return Api.ok(Api.map("status", "UP"));
  }

  @GetMapping("/auth/csrf")
  Api<?> csrf(CsrfToken token) {
    return Api.ok(Api.map("token", token.getToken(), "headerName", token.getHeaderName()));
  }

  @PostMapping("/auth/login")
  Api<?> login(
      @Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
    AppUser user = users.findByUsername(body.username()).orElse(null);
    // Use an equivalent BCrypt check even when the username is absent.
    String hash =
        user == null
            ? "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
            : user.passwordHash;
    if (!encoder.matches(body.password(), hash) || user == null)
      throw new BusinessException(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误");
    var principal = details.loadUserByUsername(user.username);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            principal, null, principal.getAuthorities()));
    request.getSession(true);
    request.changeSessionId();
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    return Api.ok(view(user));
  }

  @GetMapping("/auth/me")
  Api<?> me(Authentication a) {
    return Api.ok(view(current(a)));
  }

  AppUser current(Authentication a) {
    return users.findByUsername(a.getName()).orElseThrow(BusinessException::missing);
  }

  static Map<String, Object> view(AppUser u) {
    return Api.map(
        "id", u.id, "username", u.username, "displayName", u.displayName, "role", u.role);
  }
}
