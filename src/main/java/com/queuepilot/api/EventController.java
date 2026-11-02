package com.queuepilot.api;

import com.queuepilot.persistence.SystemEventEntity;
import com.queuepilot.persistence.SystemEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@CrossOrigin(origins = "*")
public class EventController {

    private final SystemEventRepository eventRepository;

    @Autowired
    public EventController(SystemEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @GetMapping
    public ResponseEntity<List<SystemEventEntity>> getRecentEvents() {
        if (eventRepository == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(eventRepository.findTop50ByOrderByTimestampDesc());
    }
}
