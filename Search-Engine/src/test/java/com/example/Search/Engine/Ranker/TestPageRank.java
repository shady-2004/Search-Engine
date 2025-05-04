package com.example.Search.Engine.Ranker;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;

public class TestPageRank {

    @Test
    void testPageRanker() throws InterruptedException, SQLException {
        // arrange
        PageRank.pageRank();
    }
}
