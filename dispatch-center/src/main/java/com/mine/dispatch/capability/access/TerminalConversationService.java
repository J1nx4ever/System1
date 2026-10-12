package com.mine.dispatch.capability.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.mine.dispatch.model.*;
import com.mine.dispatch.web.RealtimeHub;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class TerminalConversationService {
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private final TerminalMessageRepository repository;
    private final TerminalConversationMqttClient mqtt;
    private final RealtimeHub realtime;
    public TerminalConversationService(TerminalMessageRepository r,TerminalConversationMqttClient m,RealtimeHub h){repository=r;mqtt=m;realtime=h;}

    public List<TerminalMessage> list(String sim,AuthPrincipal p,String type,Integer id,int limit){
        return list(sim,p,type,id,limit,null);
    }
    public List<TerminalMessage> list(String sim,AuthPrincipal p,String type,Integer id,int limit,String runId){
        if(p.dispatcher()){
            if((type==null)!=(id==null))bad(HttpStatus.BAD_REQUEST,"target type and id must be supplied together");
        } else {
            if(!sim.equals(p.simulationId()))bad(HttpStatus.FORBIDDEN,"cross-simulation access denied");
            if(type!=null&&(!type.equals(p.role())||!Objects.equals(id,p.subjectId())))bad(HttpStatus.FORBIDDEN,"cross-terminal message access denied");
            type=p.role();id=p.subjectId();
        }
        try{return repository.list(sim,type,id,limit,runId);}catch(SQLException e){throw new IllegalStateException(e);}
    }

    public TerminalMessage send(String sim,AuthPrincipal p,TerminalMessageRequest request){
        if(!p.dispatcher())bad(HttpStatus.FORBIDDEN,"only dispatcher may send messages");
        if(!validSim(sim)||request.objectId()==null||request.objectId()<0||request.body()==null||request.body().isBlank())bad(HttpStatus.BAD_REQUEST,"invalid terminal message");
        String target=request.objectType();if(!"VEHICLE".equals(target)&&!"SHOVEL".equals(target))bad(HttpStatus.BAD_REQUEST,"target must be VEHICLE or SHOVEL");
        if(request.replyToMessageId()!=null){
            TerminalMessage original=find(request.replyToMessageId());
            if(!sim.equals(original.simulationId())||!target.equals(original.objectType())||original.objectId()!=request.objectId())bad(HttpStatus.BAD_REQUEST,"reply target mismatch");
        }
        long now=System.currentTimeMillis();TerminalMessage message=new TerminalMessage(UUID.randomUUID().toString(),sim,target,request.objectId(),"OUTBOUND","DISPATCHER",p.username(),request.body().strip(),now,"PENDING",request.replyToMessageId(),"");
        persist(message);broadcast(message);
        try{mqtt.publish(message);}
        catch(Exception e){message=withStatus(message,"FAILED",Objects.toString(e.getMessage(),"MQTT publish failed"));update(message);broadcast(message);throw new AccessDeniedException(HttpStatus.SERVICE_UNAVAILABLE,"mqtt_publish_failed","MQTT发送失败："+message.statusDetail());}
        try{repository.markSent(message.messageId());}
        catch(SQLException e){throw new IllegalStateException("message published but status persistence failed",e);}
        message=find(message.messageId());broadcast(message);return message;
    }
    public Map<String,String> replyFromWeb(String sim,AuthPrincipal p,String outboundId,TerminalReplyRequest request){
        if(p.dispatcher()||!sim.equals(p.simulationId())||p.subjectId()==null)bad(HttpStatus.FORBIDDEN,"terminal identity required");
        TerminalMessage original=find(outboundId);
        if(!sim.equals(original.simulationId())||!p.role().equals(original.objectType())||p.subjectId()!=original.objectId()||!"OUTBOUND".equals(original.direction()))bad(HttpStatus.FORBIDDEN,"message is not addressed to this terminal");
        if(!"SENT".equals(original.status())&&!"DELIVERED".equals(original.status()))bad(HttpStatus.CONFLICT,"outbound message was not published");
        if(request.body()==null||request.body().isBlank())bad(HttpStatus.BAD_REQUEST,"reply text is required");
        String text=request.body().strip(),id=UUID.randomUUID().toString();
        try{
            mqtt.publishResponse(sim,p.role(),p.subjectId(),id,outboundId,p.username(),text,System.currentTimeMillis());
            return Map.of("messageId",id,"status","PUBLISHED");
        }catch(AccessDeniedException e){throw e;}
        catch(Exception e){throw new AccessDeniedException(HttpStatus.SERVICE_UNAVAILABLE,"mqtt_publish_failed","MQTT回复发送失败："+Objects.toString(e.getMessage(),"connection unavailable"));}
    }



    public synchronized TerminalMessage receive(String topic,JsonNode body){
        String[] parts=topic.split("/");
        if(parts.length!=7||!"mine".equals(parts[0])||!"sim".equals(parts[1])||!"terminal".equals(parts[3])||!"response".equals(parts[6]))bad(HttpStatus.BAD_REQUEST,"invalid response topic");
        String sim=parts[2],type=parts[4],idText=parts[5];
        if(!validSim(sim)||!("VEHICLE".equals(type)||"SHOVEL".equals(type))||!idText.matches("\\d+"))bad(HttpStatus.BAD_REQUEST,"invalid response identity");
        int objectId=Integer.parseInt(idText);
        if(body.path("schemaVersion").asInt()!=1||!sim.equals(body.path("simulationId").asText())||!type.equals(body.path("terminalType").asText())||objectId!=body.path("terminalId").asInt())bad(HttpStatus.BAD_REQUEST,"topic/payload identity mismatch");
        String messageId=body.path("messageId").asText(),text=body.path("text").asText(),reply=body.path("replyToMessageId").asText(null);
        if(!ID.matcher(messageId).matches()||reply==null||!ID.matcher(reply).matches()||text.isBlank()||text.length()>1000||body.path("sentAtMs").asLong()<=0)bad(HttpStatus.BAD_REQUEST,"invalid response fields");
        String senderName=body.path("senderName").asText(type+" "+objectId);
        if(senderName.isBlank()||senderName.length()>100)bad(HttpStatus.BAD_REQUEST,"invalid response sender");
        TerminalMessage original=find(reply);
        if(!sim.equals(original.simulationId())||!type.equals(original.objectType())||objectId!=original.objectId()||!"OUTBOUND".equals(original.direction()))bad(HttpStatus.BAD_REQUEST,"response does not match an outbound message");
        TerminalMessage message=new TerminalMessage(messageId,sim,type,objectId,"INBOUND",type,senderName,text,System.currentTimeMillis(),"RECEIVED",reply,"");
        try{
            Optional<TerminalMessage> previous=repository.find(messageId);
            if(previous.isPresent()){
                TerminalMessage saved=previous.get();
                if(!saved.simulationId().equals(sim)||!saved.objectType().equals(type)||saved.objectId()!=objectId||!saved.body().equals(text)||!reply.equals(saved.replyToMessageId()))bad(HttpStatus.CONFLICT,"duplicate response id has different content");
                return saved;
            }
            repository.insert(message);
            repository.updateStatus(reply,"DELIVERED","已收到终端回执");
        }catch(SQLException e){throw new IllegalStateException("terminal response persistence failed",e);}
        broadcast(withStatus(original,"DELIVERED","已收到终端回执"));broadcast(message);return message;
    }

    private TerminalMessage find(String id){try{return repository.find(id).orElseThrow(()->new AccessDeniedException(HttpStatus.NOT_FOUND,"message_not_found","消息不存在"));}catch(SQLException e){throw new IllegalStateException(e);}}
    private void persist(TerminalMessage m){try{repository.insert(m);}catch(SQLException e){throw new IllegalStateException("terminal message persistence failed",e);}}
    private void update(TerminalMessage m){try{repository.updateStatus(m.messageId(),m.status(),m.statusDetail());}catch(SQLException e){throw new IllegalStateException(e);}}
    private void broadcast(TerminalMessage m){realtime.broadcast(RealtimeEvent.of("terminalMessage",m.simulationId(),m));}
    private static TerminalMessage withStatus(TerminalMessage m,String s,String d){return new TerminalMessage(m.messageId(),m.simulationId(),m.objectType(),m.objectId(),m.direction(),m.senderRole(),m.senderName(),m.body(),m.createdAtMs(),s,m.replyToMessageId(),d);}
    private static boolean validSim(String s){return s!=null&&s.matches("[A-Za-z0-9_-]{1,64}");}
    private static void bad(HttpStatus status,String message){throw new AccessDeniedException(status,"terminal_message_invalid",message);}
}
