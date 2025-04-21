package com.example.Search.Engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import com.example.Search.Engine.Indexer.Indexer;
import java.sql.SQLException;

@SpringBootApplication
public class SearchEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(SearchEngineApplication.class, args);
	}

	@Bean
	public Indexer indexer() throws SQLException {
		return new Indexer();
	}

}
