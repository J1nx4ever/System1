package com.mine.dispatch;

import Fusion.grpc.*;
import com.mine.dispatch.capability.decision.DispatchOnnxPolicy;
import com.mine.dispatch.capability.decision.SpeedOnnxPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnnxInferenceTest {
    @Test
    void dispatchOnnxMatchesPythonReference() {
        try (DispatchOnnxPolicy policy = new DispatchOnnxPolicy("models/dispatch_ppo.onnx")) {
            List<Float> observation = new ArrayList<>();
            for (int i = 0; i < 27; i++) observation.add(i / 27f);
            DispatchOnnxPolicy.Prediction prediction = policy.predict(observation);
            assertThat(prediction.action()).isEqualTo(1);
            assertThat(prediction.logits()).containsExactly(
                    0.92750925f, 1.2653416f, -2.0273163f, 0.28958803f);
            assertThat(prediction.value()).isCloseTo(-1.0264088f,
                    org.assertj.core.data.Offset.offset(1e-5f));
            assertThat(prediction.latencyUs()).isNotNegative();
        }
    }

    @Test
    void speedGraphAndOnnxMatchPythonReference() {
        ConflictVehicleInfo conflict = ConflictVehicleInfo.newBuilder()
                .setAgentId(3).setConflictSpeed(.5).setConflictEta(.4)
                .setDiffEta(-.2).setDiffDist(1.2).build();
        StateData state = StateData.newBuilder()
                .setEgoAgentId(0).setTargetRoadNode(3).setEgoSpeed(.4)
                .setDistToInt(.5).setTtaEgo(.2).setPrevCmdAccel(-.1)
                .setHasFront(true).setFrontAgentId(1).setFrontSpeed(.3)
                .setFrontDist(.7).setFrontTtc(2.5)
                .setHasRear(true).setRearAgentId(2).setRearSpeed(.6)
                .setRearDist(.8).setHasConflict(true)
                .addConflictVehicles(conflict).build();
        ObservationDict request = ObservationDict.newBuilder()
                .addObservations(Observation.newBuilder()
                        .setAgentId("agent_0").setState(state))
                .addObservations(Observation.newBuilder().setAgentId("agent_4")
                        .setState(StateData.newBuilder().setEgoAgentId(4).setEgoSpeed(.2)))
                .build();

        try (SpeedOnnxPolicy policy = new SpeedOnnxPolicy("models/speed_actor.onnx")) {
            ActionDict result = policy.predict(request);
            assertThat(result.getActionsCount()).isEqualTo(2);
            assertThat(result.getActions(0).getAgentId()).isEqualTo("agent_0");
            assertThat(result.getActions(0).getAction(0)).isCloseTo(
                    -0.8559048f, org.assertj.core.data.Offset.offset(2e-4f));
            assertThat(result.getActions(1).getAgentId()).isEqualTo("agent_4");
            assertThat(result.getActions(1).getAction(0)).isBetween(-3f, 2f);
        }
    }
}
