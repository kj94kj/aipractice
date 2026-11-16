package com.example.aipractice;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "spring.flyway.enabled=false")
class AipracticeApplicationTests {

	@MockitoBean
	private VectorStore vectorStore;

	@Test
	void contextLoads() {
	}

}
