package com.mine.dispatch.web;

import com.mine.dispatch.capability.access.AuthService;
import com.mine.dispatch.model.AuthPrincipal;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.Map;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;public AuthController(AuthService auth){this.auth=auth;}
    @PostMapping("/login") public Map<String,Object> login(@Valid @RequestBody LoginRequest body,HttpServletRequest req,HttpServletResponse res){
        String requestId=java.util.Optional.ofNullable(req.getHeader("X-Request-ID")).orElse(java.util.UUID.randomUUID().toString());AuthService.LoginResult result=auth.login(body.username(),body.password(),requestId);res.addHeader("Set-Cookie",cookie(result.token(),Duration.ofMillis(result.expiresAtMs()-System.currentTimeMillis()),req.isSecure()).toString());return Map.of("user",result.principal(),"expiresAtMs",result.expiresAtMs());
    }
    @GetMapping("/me") public AuthPrincipal me(HttpServletRequest req){return AuthenticationFilter.principal(req);}
    @PostMapping("/logout") public Map<String,String> logout(HttpServletRequest req,HttpServletResponse res){AuthPrincipal p=AuthenticationFilter.principal(req);auth.logout(AuthenticationFilter.cookie(req),p,AuthenticationFilter.requestId(req));res.addHeader("Set-Cookie",cookie("",Duration.ZERO,req.isSecure()).toString());return Map.of("status","logged_out");}
    private static ResponseCookie cookie(String value,Duration age,boolean secure){return ResponseCookie.from(AuthService.COOKIE,value).secure(secure).httpOnly(true).sameSite("Strict").path("/").maxAge(age).build();}
    public record LoginRequest(@NotBlank @Size(max=80) String username,@NotBlank @Size(max=200) String password){}
}
