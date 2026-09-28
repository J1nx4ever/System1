package com.mine.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mine.dispatch.capability.decision.DecisionTraceRepository;
import com.mine.dispatch.capability.decision.DecisionTraceService;
import com.mine.dispatch.model.DecisionTrace;
import com.mine.dispatch.web.RealtimeHub;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DecisionTraceSamplingTest {
    @Test
    void stableCyclesAreSampledButRejectedCommandsArePromoted() throws Exception {
        DecisionTraceRepository repository = mock(DecisionTraceRepository.class);
        RealtimeHub realtime = mock(RealtimeHub.class);
        DecisionTraceService service = new DecisionTraceService(repository, new ObjectMapper(), realtime);
        AtomicReference<DecisionTrace> last = new AtomicReference<>();
        when(repository.insert(any())).thenAnswer(call -> {
            DecisionTrace trace = call.getArgument(0);
            last.set(trace);
            return trace;
        });
        when(repository.acknowledge(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());

        assertThat(record(service, "first", false, false, .2)).isTrue();
        assertThat(record(service, "stable", false, false, .2)).isFalse();
        verify(repository, times(1)).insert(any());

        service.acknowledge("stable", "EXECUTED", "ok", System.currentTimeMillis());
        verify(repository, times(1)).insert(any());

        assertThat(record(service, "rejected", false, false, .2)).isFalse();
        service.acknowledge("rejected", "REJECTED", "unsafe", System.currentTimeMillis());
        ArgumentCaptor<DecisionTrace> saved = ArgumentCaptor.forClass(DecisionTrace.class);
        verify(repository, times(2)).insert(saved.capture());
        assertThat(saved.getValue().commandId()).isEqualTo("rejected");
        verify(repository).acknowledge(eq("rejected"), eq("REJECTED"), eq("unsafe"), anyLong());

        assertThat(record(service, "risk", true, false, .2)).isTrue();
        verify(repository, times(3)).insert(any());

        assertThat(record(service, "failed", true, false, .2)).isFalse();
        when(repository.markPublishFailed(eq("failed"), eq("offline"), anyLong()))
                .thenAnswer(call -> last.get());
        service.publishFailed("failed", "offline");
        verify(repository, times(4)).insert(saved.capture());
        assertThat(last.get().commandId()).isEqualTo("failed");
    }

    private static boolean record(DecisionTraceService service, String id, boolean front,
                                  boolean conflict, double acceleration) {
        return service.recordSpeed("SIM_TEST", 1, front, conflict, acceleration,
                Map.of("hasFront", front), Map.of("accelerationMps2", acceleration),
                id, "mine/sim/SIM_TEST/command/speed/" + id);
    }
}
