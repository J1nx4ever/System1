package com.mine.dispatch.capability.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.capability.production.TlsSocketFactoryFactory;
import com.mine.dispatch.model.TerminalMessage;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dedicated MQTT conversation channel; kept separate from the field-state callback to isolate failures. */
@Component
public class TerminalConversationMqttClient implements MqttCallbackExtended {
    private final DispatchProperties properties;
    private static final Logger log=LoggerFactory.getLogger(TerminalConversationMqttClient.class);
    private final ObjectMapper json;
    private final ObjectProvider<TerminalConversationService> conversation;
    private final AtomicBoolean connecting=new AtomicBoolean();
    private volatile MqttAsyncClient client;
    private volatile String lastError="";
    private volatile boolean subscriptionReady;
    public TerminalConversationMqttClient(DispatchProperties p,ObjectMapper j,ObjectProvider<TerminalConversationService> c){properties=p;json=j;conversation=c;}

    @Scheduled(fixedDelay=5000) public void ensureConnected(){
        var mqtt=properties.getMqtt();if(!mqtt.isEnabled()||(isConnected()&&subscriptionReady)||!connecting.compareAndSet(false,true))return;
        try {
            if(isConnected()){client.subscribe("mine/sim/+/terminal/+/+/response",1).waitForCompletion(5000);subscriptionReady=true;lastError="";return;}
            if(mqtt.getUsername()!=null&&!mqtt.getUsername().isBlank()&&(mqtt.getPassword()==null||mqtt.getPassword().isBlank()))throw new IllegalStateException("MQTT password is required");
            if(client==null){client=new MqttAsyncClient(mqtt.getBrokerUri(),clientId(mqtt.getClientId()),new MemoryPersistence());client.setCallback(this);}
            MqttConnectOptions options=new MqttConnectOptions();options.setAutomaticReconnect(true);options.setCleanSession(true);options.setKeepAliveInterval(mqtt.getKeepAliveSeconds());options.setConnectionTimeout(mqtt.getConnectionTimeoutSeconds());
            if(mqtt.getUsername()!=null&&!mqtt.getUsername().isBlank()){options.setUserName(mqtt.getUsername());options.setPassword(mqtt.getPassword().toCharArray());}
            if(mqtt.getBrokerUri().startsWith("ssl://")){options.setHttpsHostnameVerificationEnabled(true);if(mqtt.getCaFile()!=null&&!mqtt.getCaFile().isBlank())options.setSocketFactory(TlsSocketFactoryFactory.fromCaFile(mqtt.getCaFile()));}
            client.connect(options).waitForCompletion(Math.max(5000,mqtt.getConnectionTimeoutSeconds()*1000L+1000));
        } catch(Exception e){lastError=Objects.toString(e.getMessage(),e.getClass().getSimpleName());log.warn("Terminal MQTT connect/subscribe failed: {}",lastError);}
        finally{connecting.set(false);}
    }
    @Override public void connectComplete(boolean reconnect,String serverURI){subscriptionReady=false;try{client.subscribe("mine/sim/+/terminal/+/+/response",1).waitForCompletion(5000);subscriptionReady=true;lastError="";log.info("Terminal MQTT response subscription ready");}catch(MqttException e){lastError=Objects.toString(e.getMessage(),"subscription failed");log.warn("Terminal MQTT subscription failed: {}",lastError);}}
    @Override public void connectionLost(Throwable cause){subscriptionReady=false;lastError=Objects.toString(cause==null?null:cause.getMessage(),"connection lost");log.warn("Terminal MQTT connection lost: {}",lastError);}
    @Override public void messageArrived(String topic,MqttMessage message){try{if(message.isRetained())throw new IllegalArgumentException("retained terminal responses are not accepted");conversation.getObject().receive(topic,json.readTree(message.getPayload()));}catch(Exception e){lastError=Objects.toString(e.getMessage(),"invalid terminal response");log.warn("Terminal MQTT response rejected on {}: {}",topic,lastError);}}
    @Override public void deliveryComplete(IMqttDeliveryToken token){}
    public void publish(TerminalMessage message)throws Exception{
        MqttAsyncClient current=client;if(current==null||!current.isConnected())throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        String topic="mine/sim/"+message.simulationId()+"/terminal/"+message.objectType()+"/"+message.objectId()+"/message";
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("schemaVersion",1);payload.put("messageId",message.messageId());payload.put("simulationId",message.simulationId());payload.put("targetType",message.objectType());payload.put("targetId",message.objectId());payload.put("senderRole","DISPATCHER");payload.put("senderName",message.senderName());payload.put("text",message.body());payload.put("sentAtMs",message.createdAtMs());payload.put("replyToMessageId",message.replyToMessageId());
        current.publish(topic,json.writeValueAsBytes(payload),1,false).waitForCompletion(5000);
    }
    public JsonNode publishResponse(String sim,String type,int id,String messageId,String replyTo,String senderName,String text,long sentAtMs)throws Exception{
        MqttAsyncClient current=client;
        if(current==null||!current.isConnected()||!subscriptionReady)throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        String topic="mine/sim/"+sim+"/terminal/"+type+"/"+id+"/response";
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("schemaVersion",1);payload.put("messageId",messageId);
        payload.put("simulationId",sim);payload.put("terminalType",type);payload.put("terminalId",id);
        payload.put("senderName",senderName);payload.put("text",text);
        payload.put("sentAtMs",sentAtMs);payload.put("replyToMessageId",replyTo);
        byte[] bytes=json.writeValueAsBytes(payload);
        current.publish(topic,bytes,1,false).waitForCompletion(5000);
        return json.readTree(bytes);
    }
    public boolean isConnected(){MqttAsyncClient c=client;return c!=null&&c.isConnected();}
    public String lastError(){return lastError;}
    @PreDestroy public void close(){MqttAsyncClient c=client;if(c==null)return;try{if(c.isConnected())c.disconnect().waitForCompletion(3000);c.close();}catch(MqttException ignored){}}
    private static String clientId(String configured){if(configured!=null&&!configured.isBlank())return configured+"-chat";String host;try{host=InetAddress.getLocalHost().getHostName().replaceAll("[^A-Za-z0-9_-]","-");}catch(Exception e){host="local";}return "dispatch-chat-"+host+"-"+UUID.randomUUID().toString().substring(0,8);}
}
