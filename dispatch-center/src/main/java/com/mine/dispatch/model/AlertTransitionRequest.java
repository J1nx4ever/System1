package com.mine.dispatch.model;

import jakarta.validation.constraints.Size;

public record AlertTransitionRequest(@Size(max = 1000) String detail) {}
