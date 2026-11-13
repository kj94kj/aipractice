package com.example.aipractice.rag;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.aipractice.rag.DocumentIngestionService.IngestResult;
import com.example.aipractice.rag.RagService.RagAnswer;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final DocumentIngestionService ingestionService;
    private final RagService ragService;

    public RagController(
            DocumentIngestionService ingestionService,
            RagService ragService) {

        this.ingestionService = ingestionService;
        this.ragService = ragService;
    }

    @PostMapping(
            value = "/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<IngestResult> upload(
            @RequestPart("file") MultipartFile file) {

        return ResponseEntity.ok(ingestionService.ingest(file));
    }

    @PostMapping("/ask")
    public ResponseEntity<RagAnswer> ask(
            @RequestBody AskRequest request) {

        if (request == null || !StringUtils.hasText(request.question())) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(
                ragService.ask(request.question(), request.topK())
        );
    }

    public record AskRequest(
            String question,
            Integer topK
    ) {}
}
