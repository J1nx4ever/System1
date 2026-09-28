package com.mine.dispatch.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.model.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RealtimeHub {
    private static final Logger log=LoggerFactory.getLogger(RealtimeHub.class);
    private final ObjectMapper json;private final Map<String,Client> clients=new ConcurrentHashMap<>();
    public RealtimeHub(ObjectMapper json){this.json=json;}
    public WebSocketSession register(WebSocketSession session,String simulationId,AuthPrincipal principal){WebSocketSession safe=new ConcurrentWebSocketSessionDecorator(session,5000,1024*1024);clients.put(session.getId(),new Client(simulationId,safe,principal));return safe;}
    public void remove(String id){clients.remove(id);}public void send(WebSocketSession s,RealtimeEvent e)throws IOException{s.sendMessage(new TextMessage(serialize(e)));}
    public void broadcast(RealtimeEvent event){clients.forEach((id,c)->{if(!c.simulationId.equals(event.simulationId()))return;RealtimeEvent projected=project(event,c.principal);if(projected==null)return;try{if(c.session.isOpen())c.session.sendMessage(new TextMessage(serialize(projected)));else clients.remove(id);}catch(IOException e){clients.remove(id);try{c.session.close();}catch(IOException ignored){}}});}
    public int clientCount(){return clients.size();}
    private String serialize(RealtimeEvent event)throws JsonProcessingException{return json.writeValueAsString(event);}
    static RealtimeEvent project(RealtimeEvent e,AuthPrincipal p){if(p.dispatcher())return e;Object d=e.data();return switch(e.type()){
        case "map","simulationRestart"->e;
        case "vehicle"->d instanceof VehicleStateView v&&(("VEHICLE".equals(p.role())&&v.vehicleId()==p.subjectId())||("SHOVEL".equals(p.role())&&target(p.subjectId()).equals(v.destinationNode())))?e:null;
        case "shovel"->d instanceof ShovelStateView s&&"SHOVEL".equals(p.role())&&s.shovelId()==p.subjectId()?e:null;
        case "decisionTrace"->d instanceof DecisionTrace t&&(("VEHICLE".equals(p.role())&&Objects.equals(t.vehicleId(),p.subjectId()))||("SHOVEL".equals(p.role())&&Objects.equals(t.shovelId(),p.subjectId())))?e:null;
        case "manualDispatch"->d instanceof ManualDispatchCommand c&&"VEHICLE".equals(p.role())&&c.vehicleId()==p.subjectId()?e:null;
        case "terminalAction"->d instanceof TerminalAction a&&a.objectType().equals(p.role())&&a.objectId()==p.subjectId()?e:null;
        case "alert"->d instanceof OperationalAlert a&&a.objectType().equals(p.role())&&a.objectId()==p.subjectId()?e:null;
        case "terminalMessage"->d instanceof TerminalMessage m&&m.objectType().equals(p.role())&&m.objectId()==p.subjectId()?e:null;
        case "status"->d instanceof StatusView s?RealtimeEvent.of("status",e.simulationId(),"VEHICLE".equals(p.role())?new StatusView(s.simulationId(),s.serverTimeMs(),s.online(),Map.of(p.subjectId(),s.vehicleStale().getOrDefault(p.subjectId(),true)),Map.of()):new StatusView(s.simulationId(),s.serverTimeMs(),s.online(),Map.of(),Map.of(p.subjectId(),s.shovelStale().getOrDefault(p.subjectId(),true)))):null;
        default->null;};}
    public static String target(int id){return switch(id){case 0->"ShovelOreHigh1";case 1->"ShovelOreHigh2";case 2->"ShovelOreLow";case 3->"ShovelWaste";default->"";};}
    private record Client(String simulationId,WebSocketSession session,AuthPrincipal principal){}
}
