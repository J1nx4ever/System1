package com.mine.dispatch.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TerminalMessageRequest(
        @Pattern(regexp = "VEHICLE|SHOVEL") String objectType,
        Integer objectId,
        @NotBlank @Size(max = 1000) String body,
        @Size(max = 64) String replyToMessageId) {}
