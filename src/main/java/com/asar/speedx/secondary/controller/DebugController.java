package com.asar.speedx.secondary.controller;

import com.asar.speedx.secondary.service.CaseAssignmentScheduler;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/debug")
public class DebugController {

    private final CaseAssignmentScheduler scheduler;

    public DebugController(CaseAssignmentScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @PostMapping("/run-once")
    public String runOnce() {
        scheduler.runAssignment();
        return "Triggered poller.";
    }
}