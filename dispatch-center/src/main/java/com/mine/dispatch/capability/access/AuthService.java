package com.mine.dispatch.capability.access;

import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.model.AuthPrincipal;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

@Service
public class AuthService {
    public static final String COOKIE = "DISPATCH_SESSION";
    private final AccessRepository repository;
    private final DispatchProperties properties;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();
    public AuthService(AccessRepository repository, DispatchProperties properties, AuditService audit) {this.repository=repository;this.properties=properties;this.audit=audit;}

    @PostConstruct void bootstrap() throws SQLException {
        String configured=properties.getSecurity().getBootstrapUsers();
        if(configured==null||configured.isBlank())return;
        for(String spec:configured.split(";")){
            String[] p=spec.strip().split("\\|",-1);
            if(p.length!=5)throw new IllegalStateException("SECURITY_BOOTSTRAP_USERS entry must have 5 fields");
            String role=p[2].strip().toUpperCase();String sim=blank(p[3]);Integer subject=blank(p[4])==null?null:Integer.valueOf(p[4]);
            if(!role.matches("DISPATCHER|VEHICLE|SHOVEL"))throw new IllegalStateException("invalid bootstrap role");
            if("DISPATCHER".equals(role)){sim=null;subject=null;}else if(sim==null||subject==null)throw new IllegalStateException("terminal identity binding is required");
            repository.createUser(p[0].strip(),PasswordHasher.hash(p[1]),role,sim,subject);
        }
    }
    public LoginResult login(String username,String password,String requestId){
        try{
            AccessRepository.UserRow user=repository.user(username).orElse(null);
            if(user==null||!PasswordHasher.verify(password,user.passwordHash())){audit.record(null,requestId,"LOGIN","USER",username,"DENIED","invalid credentials");throw new AccessDeniedException(HttpStatus.UNAUTHORIZED,"invalid_credentials","用户名或密码错误");}
            byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);long now=System.currentTimeMillis();long expires=now+Duration.ofHours(Math.max(1,properties.getSecurity().getSessionHours())).toMillis();repository.createSession(hash(token),user.userId(),now,expires);audit.record(user.principal(),requestId,"LOGIN","USER",username,"SUCCESS","session created");return new LoginResult(token,expires,user.principal());
        }catch(AccessDeniedException e){throw e;}catch(Exception e){throw new IllegalStateException("login failed",e);}
    }
    public Optional<AuthPrincipal> authenticate(String token){if(!properties.getSecurity().isEnabled())return Optional.of(new AuthPrincipal("security-disabled","development-dispatcher","DISPATCHER",null,null));if(token==null||token.isBlank())return Optional.empty();try{return repository.session(hash(token),System.currentTimeMillis());}catch(SQLException e){throw new IllegalStateException("session lookup failed",e);}}
    public void logout(String token,AuthPrincipal principal,String requestId){try{if(token!=null)repository.deleteSession(hash(token));audit.record(principal,requestId,"LOGOUT","USER",principal.username(),"SUCCESS","session deleted");}catch(SQLException e){throw new IllegalStateException("logout failed",e);}}
    public boolean enabled(){return properties.getSecurity().isEnabled();}
    private static String hash(String value){try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String blank(String v){return v==null||v.isBlank()?null:v.strip();}
    public record LoginResult(String token,long expiresAtMs,AuthPrincipal principal){}
}
