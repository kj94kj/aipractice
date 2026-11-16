package com.example.aipractice.rag;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.aipractice.rag.DocumentIngestionService.IngestResult;
import com.example.aipractice.rag.DocumentIngestionService.DocumentDetails;
import com.example.aipractice.rag.DocumentIngestionService.DocumentSummary;
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
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "500") Integer chunkSize,
            @RequestParam(defaultValue = "50") Integer overlap) {

        return ResponseEntity.ok(ingestionService.ingest(
                file, title, category, chunkSize, overlap
        ));
    }

    @GetMapping("/documents")
    public ResponseEntity<List<DocumentSummary>> documents() {
        return ResponseEntity.ok(ingestionService.list());
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<DocumentDetails> document(
            @PathVariable UUID documentId) {
        return ResponseEntity.ok(ingestionService.get(documentId));
    }

    @PutMapping(
            value = "/documents/{documentId}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<IngestResult> update(
            @PathVariable UUID documentId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer chunkSize,
            @RequestParam(required = false) Integer overlap) {
        return ResponseEntity.ok(ingestionService.update(
                documentId, file, title, category, chunkSize, overlap
        ));
    }

    @PostMapping("/documents/{documentId}/reindex")
    public ResponseEntity<IngestResult> reindex(
            @PathVariable UUID documentId,
            @RequestBody(required = false) ReindexRequest request) {
        ReindexRequest options = request == null ? new ReindexRequest(null, null) : request;
        return ResponseEntity.ok(ingestionService.reindex(
                documentId, options.chunkSize(), options.overlap()
        ));
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID documentId) {
        ingestionService.delete(documentId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
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

    public record ReindexRequest(Integer chunkSize, Integer overlap) {}
}
