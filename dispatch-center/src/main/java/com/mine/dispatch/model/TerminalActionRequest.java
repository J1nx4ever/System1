package com.mine.dispatch.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TerminalActionRequest(
        @NotBlank @Pattern(regexp = "REQUEST|EXCEPTION|TASK_CONFIRMATION") String actionType,
        @Size(max = 1000) String detail,
        @Size(max = 64) String commandId,
        @Pattern(regexp = "INFO|WARNING|CRITICAL") String severity,
        @Size(max = 64) String code) {}
