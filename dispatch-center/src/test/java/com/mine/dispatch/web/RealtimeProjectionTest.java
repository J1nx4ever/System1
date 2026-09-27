package com.mine.dispatch.web;

import com.mine.dispatch.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RealtimeProjectionTest {
    @Test void incrementalEventsAreFilteredByBoundIdentity() {
        AuthPrincipal truck0 = new AuthPrincipal("u0", "truck0", "VEHICLE", "SIM", 0);
        AuthPrincipal shovel0 = new AuthPrincipal("s0", "shovel0", "SHOVEL", "SIM", 0);
        VehicleStateView v0 = vehicle(0, "ShovelOreHigh1");
        VehicleStateView v1 = vehicle(1, "ShovelOreHigh2");
        ShovelStateView s0 = shovel(0), s1 = shovel(1);

        assertThat(RealtimeHub.project(RealtimeEvent.of("vehicle", "SIM", v0), truck0)).isNotNull();
        assertThat(RealtimeHub.project(RealtimeEvent.of("vehicle", "SIM", v1), truck0)).isNull();
        assertThat(RealtimeHub.project(RealtimeEvent.of("shovel", "SIM", s0), truck0)).isNull();
        assertThat(RealtimeHub.project(RealtimeEvent.of("vehicle", "SIM", v0), shovel0)).isNotNull();
        assertThat(RealtimeHub.project(RealtimeEvent.of("vehicle", "SIM", v1), shovel0)).isNull();
        assertThat(RealtimeHub.project(RealtimeEvent.of("shovel", "SIM", s0), shovel0)).isNotNull();
        assertThat(RealtimeHub.project(RealtimeEvent.of("shovel", "SIM", s1), shovel0)).isNull();
    }

    @Test void initialSnapshotsContainOnlyAuthorizedProjection() {
        SimulationSnapshot all = new SimulationSnapshot("SIM", 1, true, 1, 1, null,
                List.of(vehicle(0, "ShovelOreHigh1"), vehicle(1, "ShovelOreHigh2")),
                List.of(shovel(0), shovel(1)));
        SimulationSnapshot vehicle = StateWebSocketHandler.project(all,
                new AuthPrincipal("u0", "truck0", "VEHICLE", "SIM", 0));
        assertThat(vehicle.vehicles()).extracting(VehicleStateView::vehicleId).containsExactly(0);
        assertThat(vehicle.shovels()).isEmpty();
        SimulationSnapshot shovel = StateWebSocketHandler.project(all,
                new AuthPrincipal("s0", "shovel0", "SHOVEL", "SIM", 0));
        assertThat(shovel.shovels()).extracting(ShovelStateView::shovelId).containsExactly(0);
        assertThat(shovel.vehicles()).extracting(VehicleStateView::vehicleId).containsExactly(0);
    }

    private static VehicleStateView vehicle(int id,String target){return new VehicleStateView("SIM",id+1,1,1,id,0,0,0,false,0,"",target,"",0,-1,"MoveSegment",1,false);}
    private static ShovelStateView shovel(int id){return new ShovelStateView("SIM",id+1,1,id,"S"+id,0,0,true,false,0,0,0,0,true,1,false);}
}
