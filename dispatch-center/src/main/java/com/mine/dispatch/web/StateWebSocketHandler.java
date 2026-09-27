package com.mine.dispatch.web;

import com.mine.dispatch.capability.monitoring.StateViewService;
import com.mine.dispatch.model.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class StateWebSocketHandler extends TextWebSocketHandler {
    private final RealtimeHub hub;private final StateViewService views;
    public StateWebSocketHandler(RealtimeHub h,StateViewService v){hub=h;views=v;}
    @Override public void afterConnectionEstablished(WebSocketSession session)throws Exception{AuthPrincipal p=(AuthPrincipal)session.getAttributes().get(AuthenticationFilter.PRINCIPAL);if(p==null){session.close(CloseStatus.NOT_ACCEPTABLE.withReason("authentication required"));return;}String sim=query(session,"simulationId","SIM_001");if(!p.dispatcher()&&!sim.equals(p.simulationId())){session.close(CloseStatus.POLICY_VIOLATION.withReason("cross-simulation access denied"));return;}WebSocketSession safe=hub.register(session,sim,p);hub.send(safe,RealtimeEvent.of("snapshot",sim,project(views.snapshot(sim),p)));}
    @Override public void afterConnectionClosed(WebSocketSession s,CloseStatus status){hub.remove(s.getId());}@Override public void handleTransportError(WebSocketSession s,Throwable e)throws Exception{hub.remove(s.getId());if(s.isOpen())s.close(CloseStatus.SERVER_ERROR);}
    public static SimulationSnapshot project(SimulationSnapshot s,AuthPrincipal p){if(p.dispatcher())return s;if("VEHICLE".equals(p.role()))return new SimulationSnapshot(s.simulationId(),s.serverTimeMs(),s.online(),s.lastReceivedAtMs(),s.globalMaxSequence(),s.map(),s.vehicles().stream().filter(v->v.vehicleId()==p.subjectId()).toList(),List.of());String target=RealtimeHub.target(p.subjectId());return new SimulationSnapshot(s.simulationId(),s.serverTimeMs(),s.online(),s.lastReceivedAtMs(),s.globalMaxSequence(),s.map(),s.vehicles().stream().filter(v->target.equals(v.destinationNode())).toList(),s.shovels().stream().filter(v->v.shovelId()==p.subjectId()).toList());}
    private static String query(WebSocketSession s,String n,String fallback){if(s.getUri()==null||s.getUri().getRawQuery()==null)return fallback;for(String pair:s.getUri().getRawQuery().split("&")){String[] p=pair.split("=",2);if(p.length==2&&URLDecoder.decode(p[0],StandardCharsets.UTF_8).equals(n)){String v=URLDecoder.decode(p[1],StandardCharsets.UTF_8);return v.isBlank()?fallback:v;}}return fallback;}
}
