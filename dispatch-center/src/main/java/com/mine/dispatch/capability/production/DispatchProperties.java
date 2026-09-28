package com.mine.dispatch.capability.production;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dispatch")
public class DispatchProperties {
    private final Mqtt mqtt = new Mqtt();
    private final Storage storage = new Storage();
    private final Staleness staleness = new Staleness();
    private final Inference inference = new Inference();
    private final Security security = new Security();

    public Mqtt getMqtt() { return mqtt; }
    public Storage getStorage() { return storage; }
    public Staleness getStaleness() { return staleness; }
    public Inference getInference() { return inference; }
    public Security getSecurity() { return security; }

    public static class Security {
        private boolean enabled = true;
        private long sessionHours = 12;
        private String bootstrapUsers = "";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getSessionHours() { return sessionHours; }
        public void setSessionHours(long sessionHours) { this.sessionHours = sessionHours; }
        public String getBootstrapUsers() { return bootstrapUsers; }
        public void setBootstrapUsers(String bootstrapUsers) { this.bootstrapUsers = bootstrapUsers; }
    }

    public static class Mqtt {
        private boolean enabled = true;
        private String brokerUri = "ssl://localhost:8883";
        private String clientId = "";
        private String topic = "mine/sim/+/state/#";
        private String resultTopic = "mine/sim/+/result/manual-dispatch/+";
        private String inferenceTriggerTopic = "mine/sim/+/trigger/+";
        private String decisionResultTopic = "mine/sim/+/result/decision/+";
        private String username = "field_uplink";
        private String password = "";
        private String caFile = "";
        private int keepAliveSeconds = 30;
        private int connectionTimeoutSeconds = 10;
        private int maxInflight = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBrokerUri() { return brokerUri; }
        public void setBrokerUri(String brokerUri) { this.brokerUri = brokerUri; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getResultTopic() { return resultTopic; }
        public void setResultTopic(String resultTopic) { this.resultTopic = resultTopic; }
        public String getInferenceTriggerTopic() { return inferenceTriggerTopic; }
        public void setInferenceTriggerTopic(String value) { this.inferenceTriggerTopic = value; }
        public String getDecisionResultTopic() { return decisionResultTopic; }
        public void setDecisionResultTopic(String value) { this.decisionResultTopic = value; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getCaFile() { return caFile; }
        public void setCaFile(String caFile) { this.caFile = caFile; }
        public int getKeepAliveSeconds() { return keepAliveSeconds; }
        public void setKeepAliveSeconds(int keepAliveSeconds) { this.keepAliveSeconds = keepAliveSeconds; }
        public int getConnectionTimeoutSeconds() { return connectionTimeoutSeconds; }
        public void setConnectionTimeoutSeconds(int connectionTimeoutSeconds) { this.connectionTimeoutSeconds = connectionTimeoutSeconds; }
        public int getMaxInflight() { return maxInflight; }
        public void setMaxInflight(int maxInflight) { this.maxInflight = maxInflight; }
    }

    public static class Inference {
        private boolean enabled = true;
        private String mode = "onnx";
        private String dispatchModelPath = "./models/dispatch_ppo.onnx";
        private String speedModelPath = "./models/speed_actor.onnx";
        private String dispatchHost = "127.0.0.1";
        private int dispatchPort = 50051;
        private long dispatchTimeoutMillis = 10000;
        private String speedHost = "127.0.0.1";
        private int speedPort = 50060;
        private long speedTimeoutMillis = 150;
        private double minAccelerationMps2 = -5.0;
        private double maxAccelerationMps2 = 3.0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getDispatchModelPath() { return dispatchModelPath; }
        public void setDispatchModelPath(String value) { this.dispatchModelPath = value; }
        public String getSpeedModelPath() { return speedModelPath; }
        public void setSpeedModelPath(String value) { this.speedModelPath = value; }
        public String getDispatchHost() { return dispatchHost; }
        public void setDispatchHost(String dispatchHost) { this.dispatchHost = dispatchHost; }
        public int getDispatchPort() { return dispatchPort; }
        public void setDispatchPort(int dispatchPort) { this.dispatchPort = dispatchPort; }
        public long getDispatchTimeoutMillis() { return dispatchTimeoutMillis; }
        public void setDispatchTimeoutMillis(long value) { this.dispatchTimeoutMillis = value; }
        public String getSpeedHost() { return speedHost; }
        public void setSpeedHost(String speedHost) { this.speedHost = speedHost; }
        public int getSpeedPort() { return speedPort; }
        public void setSpeedPort(int speedPort) { this.speedPort = speedPort; }
        public long getSpeedTimeoutMillis() { return speedTimeoutMillis; }
        public void setSpeedTimeoutMillis(long value) { this.speedTimeoutMillis = value; }
        public double getMinAccelerationMps2() { return minAccelerationMps2; }
        public void setMinAccelerationMps2(double value) { this.minAccelerationMps2 = value; }
        public double getMaxAccelerationMps2() { return maxAccelerationMps2; }
        public void setMaxAccelerationMps2(double value) { this.maxAccelerationMps2 = value; }
    }

    public static class Storage {
        private String jdbcUrl = "jdbc:sqlite:./data/dispatch-center.db";
        private int writerQueueCapacity = 50000;

        public String getJdbcUrl() { return jdbcUrl; }
        public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
        public int getWriterQueueCapacity() { return writerQueueCapacity; }
        public void setWriterQueueCapacity(int writerQueueCapacity) { this.writerQueueCapacity = writerQueueCapacity; }
    }

    public static class Staleness {
        private long vehicleMillis = 3000;
        private long shovelMillis = 3000;
        private long simulationMillis = 5000;

        public long getVehicleMillis() { return vehicleMillis; }
        public void setVehicleMillis(long vehicleMillis) { this.vehicleMillis = vehicleMillis; }
        public long getShovelMillis() { return shovelMillis; }
        public void setShovelMillis(long shovelMillis) { this.shovelMillis = shovelMillis; }
        public long getSimulationMillis() { return simulationMillis; }
        public void setSimulationMillis(long simulationMillis) { this.simulationMillis = simulationMillis; }
    }
}
