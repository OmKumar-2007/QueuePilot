package com.queuepilot.coordinator.history;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HistoryController {

    private final HistoryService history;

    public HistoryController(HistoryService history) {
        this.history = history;
    }

    @PostMapping("/api/admin/history/clear")
    public HistoryService.Cleared clear() {
        return history.clear();
    }
}
