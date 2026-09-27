package com.mine.dispatch.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.access.AuthService;
import com.mine.dispatch.capability.access.AuditService;
import com.mine.dispatch.model.AuthPrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

@Component
public class AuthenticationFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL="dispatchPrincipal", REQUEST_ID="dispatchRequestId";
    private final AuthService auth;private final AuditService audit;private final ObjectMapper json;
    public AuthenticationFilter(AuthService auth,AuditService audit,ObjectMapper json){this.auth=auth;this.audit=audit;this.json=json;}
    @Override protected boolean shouldNotFilter(HttpServletRequest r){String p=r.getRequestURI();return !p.startsWith("/api/v1/")||p.equals("/api/v1/auth/login")||p.equals("/api/v1/health");}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
        String requestId=Optional.ofNullable(req.getHeader("X-Request-ID")).filter(v->v.matches("[A-Za-z0-9._-]{1,80}")).orElse(UUID.randomUUID().toString());req.setAttribute(REQUEST_ID,requestId);res.setHeader("X-Request-ID",requestId);
        String token=cookie(req);Optional<AuthPrincipal> p=auth.authenticate(token);if(p.isEmpty()){audit.record(null,requestId,"HTTP_"+req.getMethod(),"PATH",req.getRequestURI(),"DENIED","authentication required");res.setStatus(401);res.setCharacterEncoding("UTF-8");res.setContentType("application/json");json.writeValue(res.getWriter(),Map.of("error","authentication_required","message","请先登录","requestId",requestId));return;}req.setAttribute(PRINCIPAL,p.get());chain.doFilter(req,res);
    }
    public static AuthPrincipal principal(HttpServletRequest r){return (AuthPrincipal)r.getAttribute(PRINCIPAL);}public static String requestId(HttpServletRequest r){return (String)r.getAttribute(REQUEST_ID);}
    public static String cookie(HttpServletRequest r){if(r.getCookies()!=null)for(Cookie c:r.getCookies())if(AuthService.COOKIE.equals(c.getName()))return c.getValue();return null;}
}
