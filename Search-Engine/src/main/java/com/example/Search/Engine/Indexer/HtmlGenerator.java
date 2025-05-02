package com.example.Search.Engine.Indexer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class HtmlGenerator {
    private static final String[] TOPICS = {
        "Artificial Intelligence", "Machine Learning", "Data Science", "Web Development",
        "Cybersecurity", "Cloud Computing", "Blockchain", "Internet of Things",
        "Quantum Computing", "Robotics", "Virtual Reality", "Augmented Reality",
        "5G Technology", "Edge Computing", "DevOps", "Microservices",
        "Big Data", "Natural Language Processing", "Computer Vision", "Deep Learning"
    };

    private static final String[] LOREM_IPSUM = {
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.",
        "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.",
        "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur.",
        "Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.",
        "Sed ut perspiciatis unde omnis iste natus error sit voluptatem accusantium doloremque laudantium.",
        "Nemo enim ipsam voluptatem quia voluptas sit aspernatur aut odit aut fugit, sed quia consequuntur magni dolores.",
        "Neque porro quisquam est, qui dolorem ipsum quia dolor sit amet, consectetur, adipisci velit.",
        "Quis autem vel eum iure reprehenderit qui in ea voluptate velit esse quam nihil molestiae consequatur.",
        "At vero eos et accusamus et iusto odio dignissimos ducimus qui blanditiis praesentium voluptatum deleniti atque.",
        "Et harum quidem rerum facilis est et expedita distinctio. Nam libero tempore, cum soluta nobis est eligendi."
    };

    private static final Random random = new Random();

    public static void generateHtmlFiles(int count) throws IOException {
        String basePath = "Search-Engine/src/main/resources/filesToIndex";
        Path directory = Paths.get(basePath);
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
        }

        for (int i = 1; i <= count; i++) {
            String fileName = String.format("article_%03d.html", i);
            File file = new File(directory.toFile(), fileName);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(generateHtmlContent(i));
                System.out.println("Generated: " + fileName);
            }
        }
    }

    private static String generateHtmlContent(int index) {
        String topic = TOPICS[random.nextInt(TOPICS.length)];
        StringBuilder content = new StringBuilder();
        
        content.append("<!DOCTYPE html>\n");
        content.append("<html lang=\"en\">\n");
        content.append("<head>\n");
        content.append("    <meta charset=\"UTF-8\">\n");
        content.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        content.append(String.format("    <title>%s - Article %d</title>\n", topic, index));
        content.append("    <style>\n");
        content.append("        body { font-family: Arial, sans-serif; line-height: 1.6; margin: 40px; }\n");
        content.append("        h1 { color: #2c3e50; }\n");
        content.append("        h2 { color: #34495e; }\n");
        content.append("        p { margin-bottom: 20px; }\n");
        content.append("    </style>\n");
        content.append("</head>\n");
        content.append("<body>\n");
        
        content.append(String.format("    <h1>%s: A Comprehensive Overview</h1>\n", topic));
        
        // Generate 3-5 sections
        int sections = 3 + random.nextInt(3);
        for (int i = 1; i <= sections; i++) {
            content.append(String.format("    <h2>Section %d: %s</h2>\n", i, 
                generateSectionTitle(topic)));
            
            // Generate 3-5 paragraphs per section
            int paragraphs = 3 + random.nextInt(3);
            for (int j = 0; j < paragraphs; j++) {
                content.append("    <p>");
                content.append(generateParagraph());
                content.append("</p>\n");
            }
        }
        
        content.append("</body>\n");
        content.append("</html>");
        
        return content.toString();
    }

    private static String generateSectionTitle(String topic) {
        String[] prefixes = {
            "Understanding", "Exploring", "The Future of", "Applications of",
            "Challenges in", "Best Practices for", "Implementing", "Advanced Topics in"
        };
        return prefixes[random.nextInt(prefixes.length)] + " " + topic;
    }

    private static String generateParagraph() {
        StringBuilder paragraph = new StringBuilder();
        int sentences = 3 + random.nextInt(4);
        for (int i = 0; i < sentences; i++) {
            if (i > 0) paragraph.append(" ");
            paragraph.append(LOREM_IPSUM[random.nextInt(LOREM_IPSUM.length)]);
        }
        return paragraph.toString();
    }

    public static void main(String[] args) {
        try {
            generateHtmlFiles(1000);
            System.out.println("Successfully generated 1000 HTML files!");
        } catch (IOException e) {
            System.err.println("Error generating HTML files: " + e.getMessage());
            e.printStackTrace();
        }
    }
} 