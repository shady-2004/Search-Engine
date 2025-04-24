package com.example.Search.Engine.Suggestions;

import java.util.*;
import java.util.AbstractMap.SimpleEntry;

public class Trie {
    static final int TOP_SUGGESTIONS = 10;
    TrieNode root;

    public Trie() {
        root = new TrieNode();
    }

    // Insert word with its frequency
    public void insert(String word, int freq) {
        TrieNode temp = root;
        for (char a : word.toCharArray()) {
            if (!temp.children.containsKey(a)) {
                temp.children.put(a, new TrieNode());
            }
            temp = temp.children.get(a);
            updateTopSuggestions(temp, word, freq);
        }
        temp.wordEnd = true;  // Mark the end of the word
    }

    // Update the top suggestions list for the given node
    private void updateTopSuggestions(TrieNode node, String word, int freq) {
        // Update frequency if word is already in the list
        for (SimpleEntry<String, Integer> entry : node.topSuggestions) {
            if (entry.getKey().equals(word)) {
                entry.setValue(freq);  // Update frequency
                return;
            }
        }
        // Add new word and frequency
        node.topSuggestions.add(new SimpleEntry<>(word, freq));
        node.topSuggestions.sort((p1, p2) -> p2.getValue().compareTo(p1.getValue()));  // Sort by frequency in descending order
        if (node.topSuggestions.size() > TOP_SUGGESTIONS) {
            node.topSuggestions.remove(node.topSuggestions.size() - 1);  // Keep only top N
        }
    }

    // Get top suggestions for a given prefix
    public List<String> getTopSuggestions(String prefix) {
        TrieNode temp = root;
        for (char a : prefix.toCharArray()) {
            if (!temp.children.containsKey(a)) {
                return new ArrayList<>();  // No suggestions found for this prefix
            }
            temp = temp.children.get(a);
        }

        List<String> result = new ArrayList<>();
        for (SimpleEntry<String, Integer> entry : temp.topSuggestions) {
            result.add(entry.getKey());
        }
        return result;
    }

    // TrieNode class to represent each node in the Trie
    private static class TrieNode {
        boolean wordEnd;
        HashMap<Character, TrieNode> children;
        List<SimpleEntry<String, Integer>> topSuggestions;

        TrieNode() {
            wordEnd = false;
            children = new HashMap<>();
            topSuggestions = new ArrayList<>();
        }
    }
}
