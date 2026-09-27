package com.mine.dispatch.web;
import com.mine.dispatch.capability.access.AuthorizationService;
import com.mine.dispatch.capability.tracking.CycleStoryRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class CycleStoryController {
    private final CycleStoryRepository stories;
    private final AuthorizationService auth;
    public CycleStoryController(CycleStoryRepository s,AuthorizationService a){stories=s;auth=a;}
    @GetMapping("/api/v1/runs/{runId}/cycles/{cycleId}/story")
    public Map<String,Object> story(@PathVariable String runId,@PathVariable String cycleId,HttpServletRequest r)throws Exception {
        auth.dispatcher(AuthenticationFilter.principal(r),AuthenticationFilter.requestId(r),"CYCLE_STORY","RUN",runId);
        return stories.story(runId,cycleId);
    }
}
