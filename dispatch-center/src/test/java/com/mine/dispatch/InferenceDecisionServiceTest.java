package com.mine.dispatch;

import DRL.grpc.*;
import Fusion.grpc.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.decision.DecisionTraceService;
import com.mine.dispatch.capability.decision.InferenceDecisionService;
import com.mine.dispatch.capability.production.DispatchProperties;
import com.mine.dispatch.capability.vehicledata.LatestStateStore;
import com.mine.dispatch.capability.vehicledata.MqttIngestionClient;
import com.mine.dispatch.model.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InferenceDecisionServiceTest {
    @Test
    void realUploadedGrpcContractsDriveTaskAndSpeedCommands() throws Exception {
        Server dispatchServer = ServerBuilder.forPort(0).addService(new DispatchServiceGrpc.DispatchServiceImplBase() {
            @Override public void getNextDestination(DispatchRequest request,
                    StreamObserver<DispatchResponse> observer) {
                observer.onNext(DispatchResponse.newBuilder().setHasAction(true).setAction(1)
                        .setValue(.75).addLogits(.1).addLogits(.8).setLatencyUs(321)
                        .setEpisodeId(request.getEpisodeId())
                        .setDecisionSequence(request.getDecisionSequence()).build());
                observer.onCompleted();
            }
        }).build().start();
        Server speedServer = ServerBuilder.forPort(0).addService(
                new SpeedInferenceServiceGrpc.SpeedInferenceServiceImplBase() {
                    @Override public void getAccelerations(ObservationDict request,
                            StreamObserver<ActionDict> observer) {
                        observer.onNext(ActionDict.newBuilder().addActions(Action.newBuilder()
                                .setAgentId("agent_0").addAction(.4f)).build());
                        observer.onCompleted();
                    }
                }).build().start();
        try {
            DispatchProperties properties = new DispatchProperties();
            properties.getInference().setMode("grpc");
            properties.getInference().setDispatchPort(dispatchServer.getPort());
            properties.getInference().setSpeedPort(speedServer.getPort());
            properties.getInference().setDispatchTimeoutMillis(2000);
            properties.getInference().setSpeedTimeoutMillis(2000);
            LatestStateStore states = liveState();
            MqttIngestionClient mqtt = mock(MqttIngestionClient.class);
            DecisionTraceService traces = mock(DecisionTraceService.class);
            when(traces.recordSpeed(anyString(), anyInt(), anyBoolean(), anyBoolean(), anyDouble(),
                    any(), any(), anyString(), anyString())).thenReturn(true);
            CountDownLatch published = new CountDownLatch(2);
            AtomicReference<String> taskPayload = new AtomicReference<>();
            AtomicReference<String> speedPayload = new AtomicReference<>();
            doAnswer(invocation -> {
                String topic = invocation.getArgument(0);
                String body = new String(invocation.<byte[]>getArgument(1));
                if (topic.contains("/auto-dispatch/")) taskPayload.set(body);
                if (topic.contains("/speed/")) speedPayload.set(body);
                published.countDown();
                return null;
            }).when(mqtt).publish(anyString(), any(byte[].class), eq(1), eq(false));

            try (InferenceDecisionService service = new InferenceDecisionService(
                    properties, states, mqtt, traces, new ObjectMapper())) {
                DRL.grpc.Observation.Builder observation = DRL.grpc.Observation.newBuilder()
                        .setSchemaId("dispatch.v1-27");
                for (int i = 0; i < 27; i++) observation.addValues(i / 27f);
                DispatchRequest task = DispatchRequest.newBuilder().setTruckId(0)
                        .setObs(observation).setEpisodeId("episode-1").setDecisionSequence(7)
                        .setRequestId("request-7").setActionRequired(true).build();
                service.handle("mine/sim/SIM_001/trigger/dispatch", task.toByteArray());

                StateData speedState = StateData.newBuilder().setEgoAgentId(0)
                        .setEgoSpeed(.4).setDistToInt(.5).setTtaEgo(.2).build();
                ObservationDict speed = ObservationDict.newBuilder().addObservations(
                        Fusion.grpc.Observation.newBuilder().setAgentId("agent_0")
                                .setState(speedState)).build();
                service.handle("mine/sim/SIM_001/trigger/speed", speed.toByteArray());

                assertThat(published.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(taskPayload.get()).contains("\"action\":1")
                        .contains("\"targetNode\":\"ShovelOreHigh2\"");
                assertThat(speedPayload.get()).contains("\"vehicleId\":0")
                        .contains("\"accelerationMps2\":0.4");
                verify(traces, timeout(3000)).create(eq("SIM_001"), eq("TASK"), anyString(),
                        eq(0), anyInt(), anyString(), any(), any(), isNull(),
                        anyString(), anyString(), anyString());
                verify(traces, timeout(3000)).recordSpeed(eq("SIM_001"), eq(0), anyBoolean(),
                        anyBoolean(), anyDouble(), any(), any(), anyString(), anyString());
            }
        } finally {
            dispatchServer.shutdownNow();
            speedServer.shutdownNow();
        }
    }

    private static LatestStateStore liveState() {
        long now = System.currentTimeMillis();
        LatestStateStore store = new LatestStateStore();
        store.apply(new MapStateView("SIM_001", 1, 1, "image2.png", 1920, 957,
                List.of(new RoadNodeView("dumpNode", 0, 0),
                        new RoadNodeView("ShovelOreHigh1", 1, 1),
                        new RoadNodeView("ShovelOreHigh2", 2, 2),
                        new RoadNodeView("ShovelOreLow", 3, 3),
                        new RoadNodeView("ShovelWaste", 4, 4)), List.of(), now, false));
        store.apply(new VehicleStateView("SIM_001", 2, 2, now, 0, 0, 0, 5,
                false, 0, "dumpNode", "ShovelOreHigh1", "segment", 0, -1,
                "NeedDispatchToLoadSite", now, false));
        for (int i = 0; i < 4; i++) store.apply(new ShovelStateView("SIM_001", 3 + i, 2,
                i, "S" + i, i, i, true, false, 0, 0, .3, 1000, true, now, false));
        return store;
    }
}
