package com.mine.dispatch.web;
import com.mine.dispatch.capability.access.AuthorizationService;
import com.mine.dispatch.capability.tracking.ProductionEventService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/runs/{runId}/production")
public class ProductionTrackingController {
    private final ProductionEventService service;
    private final AuthorizationService auth;
    public ProductionTrackingController(ProductionEventService s,AuthorizationService a){service=s;auth=a;}
    @GetMapping("/events") public List<ProductionEventService.Event> events(@PathVariable String runId,
            @RequestParam(required=false) Integer vehicleId,@RequestParam(defaultValue="100")int limit,
            @RequestParam(defaultValue="false")boolean latest,HttpServletRequest r)throws Exception {
        auth.dispatcher(AuthenticationFilter.principal(r),AuthenticationFilter.requestId(r),"PRODUCTION_READ","RUN",runId);
        return service.events(runId,vehicleId,limit,latest);
    }
    @GetMapping("/summary") public Map<String,Object> summary(@PathVariable String runId,HttpServletRequest r)throws Exception {
        auth.dispatcher(AuthenticationFilter.principal(r),AuthenticationFilter.requestId(r),"PRODUCTION_SUMMARY","RUN",runId);
        return service.summary(runId);
    }
}
