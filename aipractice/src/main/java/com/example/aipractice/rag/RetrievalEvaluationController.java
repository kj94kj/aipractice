package com.example.aipractice.rag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.aipractice.rag.RetrievalEvaluationService.EvaluationReport;
import com.example.aipractice.rag.RetrievalEvaluationService.EvaluationRequest;

@RestController
@RequestMapping("/api/rag/evaluations")
public class RetrievalEvaluationController {

    private final RetrievalEvaluationService evaluationService;

    public RetrievalEvaluationController(RetrievalEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping
    public ResponseEntity<EvaluationReport> evaluate(@RequestBody EvaluationRequest request) {
        return ResponseEntity.ok(evaluationService.evaluate(request));
    }
}
