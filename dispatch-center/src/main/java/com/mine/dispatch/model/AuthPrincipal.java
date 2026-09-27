package com.mine.dispatch.model;

public record AuthPrincipal(String userId, String username, String role,
                            String simulationId, Integer subjectId) {
    public boolean dispatcher() { return "DISPATCHER".equals(role); }
    public String objectType() { return dispatcher() ? "SYSTEM" : role; }
    public String objectId() { return dispatcher() ? "*" : String.valueOf(subjectId); }
}
