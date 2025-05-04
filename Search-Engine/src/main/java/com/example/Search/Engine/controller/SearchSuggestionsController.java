package com.example.Search.Engine.controller;

import com.example.Search.Engine.BackendManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.List;

@RestController
@RequestMapping("/api/suggestions")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true", methods = {RequestMethod.GET, RequestMethod.OPTIONS})
public class SearchSuggestionsController {
    private final BackendManager backendManager;

    @Autowired
    public SearchSuggestionsController(BackendManager backendManager) {
        this.backendManager = backendManager;
    }

    @GetMapping
    public ResponseEntity<List<String>> getSuggestions(@RequestParam String q) {
        if (q == null || q.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            List<String> suggestions = backendManager.getSearchSuggestions(q);
            return ResponseEntity.ok(suggestions);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
} 