package com.example.Search.Engine.Ranker;

import com.example.Search.Engine.Suggestions.Trie;
import java.util.*;

public class TestTrie {

    public static void main(String[] args) {
        // Create an instance of the Trie class
        Trie trie = new Trie();

        // Test Case 1: Insert words into the Trie
        System.out.println("Inserting words into the Trie...");
        trie.insert("hello", 10);
        trie.insert("hell", 15);
        trie.insert("heaven", 8);
        trie.insert("goodbye", 20);
        trie.insert("good", 18);
        trie.insert("go", 12);
        trie.insert("goose", 5);
        trie.insert("greeting", 17);
        trie.insert("god", 13);
        trie.insert("goodnight", 25);

        // Test Case 2: Get top suggestions for a specific prefix (no direct access to topSuggestions)
        System.out.println("\nGetting top suggestions for prefix 'go'...");
        List<String> topGoSuggestions = trie.getTopSuggestions("go");
        System.out.println("Top Suggestions for 'go':");
        for (String suggestion : topGoSuggestions) {
            System.out.println(suggestion);
        }

        System.out.println("\nGetting top suggestions for prefix 'he'...");
        List<String> topHeSuggestions = trie.getTopSuggestions("he");
        System.out.println("Top Suggestions for 'he':");
        for (String suggestion : topHeSuggestions) {
            System.out.println(suggestion);
        }

        System.out.println("\nGetting top suggestions for prefix 'good'...");
        List<String> topGoodSuggestions = trie.getTopSuggestions("good");
        System.out.println("Top Suggestions for 'good':");
        for (String suggestion : topGoodSuggestions) {
            System.out.println(suggestion);
        }

        // Test Case 3: Update word frequencies
        System.out.println("\nUpdating frequencies of existing words...");
        trie.insert("hello", 22);     // Update the frequency of "hello"
        trie.insert("goodbye", 30);   // Update the frequency of "goodbye"

        // Print the top suggestions after updating the frequencies
        System.out.println("Top Suggestions after Frequency Update for prefix 'go':");
        topGoSuggestions = trie.getTopSuggestions("go");
        for (String suggestion : topGoSuggestions) {
            System.out.println(suggestion);
        }

        System.out.println("\nTop Suggestions after Frequency Update for prefix 'he':");
        topHeSuggestions = trie.getTopSuggestions("he");
        for (String suggestion : topHeSuggestions) {
            System.out.println(suggestion);
        }
    }
}
