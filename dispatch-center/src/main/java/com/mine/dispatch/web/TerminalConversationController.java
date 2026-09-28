package com.mine.dispatch.web;

import com.mine.dispatch.capability.access.AuthorizationService;
import com.mine.dispatch.capability.access.TerminalConversationService;
import com.mine.dispatch.model.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/simulations/{simulationId}/terminal-messages")
public class TerminalConversationController {
    private final TerminalConversationService messages;
    private final AuthorizationService authorization;
    public TerminalConversationController(TerminalConversationService m,AuthorizationService a){messages=m;authorization=a;}
    @GetMapping public List<TerminalMessage> list(@PathVariable String simulationId,
            @RequestParam(required=false) String objectType,@RequestParam(required=false) Integer objectId,
            @RequestParam(defaultValue="100") int limit,HttpServletRequest request){
        AuthPrincipal p=AuthenticationFilter.principal(request);
        authorization.simulation(p,simulationId,AuthenticationFilter.requestId(request),"TERMINAL_MESSAGES_READ");
        return messages.list(simulationId,p,objectType,objectId,limit);
    }
    @PostMapping public TerminalMessage send(@PathVariable String simulationId,@Valid @RequestBody TerminalMessageRequest body,HttpServletRequest request){
        AuthPrincipal p=AuthenticationFilter.principal(request);
        authorization.simulation(p,simulationId,AuthenticationFilter.requestId(request),"TERMINAL_MESSAGE_SEND");
        return messages.send(simulationId,p,body);
    }
    @PostMapping("/{messageId}/reply") public Map<String,String> reply(@PathVariable String simulationId,
            @PathVariable String messageId,@Valid @RequestBody TerminalReplyRequest body,HttpServletRequest request){
        AuthPrincipal p=AuthenticationFilter.principal(request);
        authorization.simulation(p,simulationId,AuthenticationFilter.requestId(request),"TERMINAL_MESSAGE_REPLY");
        return messages.replyFromWeb(simulationId,p,messageId,body);
    }
}
