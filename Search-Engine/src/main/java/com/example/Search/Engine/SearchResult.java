package com.example.Search.Engine;

public class SearchResult {
    private final String url;
    private final String title;
    private final double score;

    public SearchResult(String url, String title, double score) {
        this.url = url;
        this.title = title;
        this.score = score;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public double getScore() {
        return score;
    }

    @Override
    public String toString() {
        return "SearchResult{" +
                "url='" + url + '\'' +
                ", title='" + title + '\'' +
                ", score=" + score +
                '}';
    }
} 