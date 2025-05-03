package com.example.Search.Engine.controller;

import com.example.Search.Engine.DatabaseManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for handling search requests.
 * Provides a REST API endpoint for searching documents.
 */
@RestController
@RequestMapping("/api/search")
@CrossOrigin(origins = "*") // Allow requests from any origin
public class SearchController {

    private final DatabaseManager databaseManager;

    @Autowired
    public SearchController(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @GetMapping
    public ResponseEntity<DatabaseManager.SearchResponse> search(
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        if (page < 0) {
            return ResponseEntity.badRequest().body(new DatabaseManager.SearchResponse(List.of(), 0));
        }

        if (size <= 0 || size > 100) {
            size = 10;
        }

        try {
            DatabaseManager.SearchResponse response = databaseManager.search(query, page, size);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
} 