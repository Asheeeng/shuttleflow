package edu.gzu.shuttle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(UserRepo users) {
    return name ->
        users
            .findByUsername(name)
            .map(u -> User.withUsername(u.username).password(u.passwordHash).roles(u.role).build())
            .orElseThrow(() -> new UsernameNotFoundException("登录失败"));
  }

  @Bean
  SecurityContextRepository contextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, ObjectMapper mapper, SecurityContextRepository repo) throws Exception {
    http.securityContext(c -> c.securityContextRepository(repo))
        .csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/auth/login", "/api/auth/csrf", "/api/health")
                    .permitAll()
                    .requestMatchers("/api/passenger/**")
                    .hasRole("PASSENGER")
                    .requestMatchers("/api/driver/**")
                    .hasRole("DRIVER")
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> {
                          res.setStatus(401);
                          res.setContentType("application/json;charset=UTF-8");
                          mapper.writeValue(
                              res.getWriter(), new Api<>("UNAUTHENTICATED", "请先登录", null));
                        })
                    .accessDeniedHandler(
                        (req, res, ex) -> {
                          res.setStatus(403);
                          res.setContentType("application/json;charset=UTF-8");
                          mapper.writeValue(
                              res.getWriter(), new Api<>("FORBIDDEN", "无访问权限或安全令牌已失效，请刷新重试", null));
                        }))
        .logout(
            l ->
                l.logoutUrl("/api/auth/logout")
                    .logoutSuccessHandler(
                        (req, res, auth) -> {
                          res.setContentType("application/json;charset=UTF-8");
                          mapper.writeValue(res.getWriter(), Api.ok(null));
                        }));
    return http.build();
  }
}
