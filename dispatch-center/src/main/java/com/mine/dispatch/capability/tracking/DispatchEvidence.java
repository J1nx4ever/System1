package com.mine.dispatch.capability.tracking;

import DRL.grpc.DispatchRequest;
import com.mine.dispatch.model.*;
import java.util.*;

/** Evidence only; never supplies alternative actions to the inference service. */
public final class DispatchEvidence {
    private DispatchEvidence() {}
    public static Map<String,Object> capture(DispatchRequest request,VehicleStateView vehicle,
            List<ShovelStateView> shovels,MapStateView map,String declaredVersion) {
        Map<String,Object> evidence=new LinkedHashMap<>();
        evidence.put("schemaVersion",1);
        evidence.put("capturedAtWallTimeMs",System.currentTimeMillis());
        evidence.put("sourceSimTimeSeconds",request.getSimTimeSeconds());
        evidence.put("vehicleStateAtGateway",vehicle);
        List<Object> candidates=new ArrayList<>();
        for(int id=0;id<4;id++) {
            final int site=id;
            Map<String,Object> candidate=new LinkedHashMap<>();candidate.put("shovelId",id);
            candidate.put("state",shovels.stream().filter(s->s.shovelId()==site).findFirst().orElse(null));
            candidates.add(candidate);
        }
        evidence.put("candidateStatesAtGateway",candidates);
        evidence.put("mapSequence",map==null?null:map.sequence());
        evidence.put("mapVersion",null);
        evidence.put("modelVersion",declaredVersion==null||declaredVersion.isBlank()?null:declaredVersion);
        evidence.put("modelVersionSource",declaredVersion==null||declaredVersion.isBlank()?"UNKNOWN":"OPERATOR_ENV_DECLARATION");
        evidence.put("normalizedObservation",request.getObs().getValuesList());
        evidence.put("featureSchemaId",request.getObs().getSchemaId());
        evidence.put("featureNames",List.of("windowGradeGap","windowOreProgress","windowTimeProgress",
            "shiftGradeGap","shiftOreProgress","shiftTimeProgress",
            "HG1_queueFraction","HG1_incomingFraction","HG1_utilization",
            "HG2_queueFraction","HG2_incomingFraction","HG2_utilization",
            "LG_queueFraction","LG_incomingFraction","LG_utilization",
            "WASTE_queueFraction","WASTE_incomingFraction","WASTE_utilization",
            "crusher_queueFraction","crusher_incomingFraction","crusher_utilization",
            "dump_queueFraction","dump_incomingFraction","dump_utilization",
            "origin_initial","origin_crusher","origin_dump"));
        evidence.put("rawProductionState",null);
        evidence.put("rawObservation",null);
        evidence.put("causalExplanation",null);
        evidence.put("scope","Model observation is the actual request. Gateway state is captured before inference, not guaranteed synchronous with source observation; no inverse reconstruction of clipped values.");
        return evidence;
    }
}
