package com.mine.dispatch.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TerminalReplyRequest(@NotBlank @Size(max=1000) String body) {}
