package com.example.Search.Engine.Ranker;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestPageRank {

    @Test
    void testPageRanker() throws InterruptedException, SQLException {
        // arrange
        PageRank.pageRank();
    }
}
