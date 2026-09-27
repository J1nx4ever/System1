package com.mine.dispatch.web;

import com.mine.dispatch.capability.access.AuthService;
import com.mine.dispatch.model.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.*;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;

@Configuration @EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
    private final StateWebSocketHandler handler;private final AuthService auth;
    public WebSocketConfiguration(StateWebSocketHandler h,AuthService a){handler=h;auth=a;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry r){r.addHandler(handler,"/ws/state").addInterceptors(new HandshakeInterceptor(){
        @Override public boolean beforeHandshake(ServerHttpRequest req,ServerHttpResponse res,WebSocketHandler h,Map<String,Object> attrs){if(!(req instanceof ServletServerHttpRequest sr))return false;HttpServletRequest servlet=sr.getServletRequest();AuthPrincipal p=auth.authenticate(AuthenticationFilter.cookie(servlet)).orElse(null);if(p==null)return false;attrs.put(AuthenticationFilter.PRINCIPAL,p);return true;}
        @Override public void afterHandshake(ServerHttpRequest r,ServerHttpResponse s,WebSocketHandler h,Exception e){}
    });}
}
