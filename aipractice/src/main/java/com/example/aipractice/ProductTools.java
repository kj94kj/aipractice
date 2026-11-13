package com.example.aipractice;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ProductTools {

    public record Product(
        String name,
        String category,
        int price,
        String color,
        int stock
    ) {}

    private final List<Product> products = List.of(
        new Product("나이키 운동화", "운동화", 80000, "검정", 12),
        new Product("아디다스 운동화", "운동화", 120000, "흰색", 5),
        new Product("뉴발란스 운동화", "운동화", 95000, "검정", 0),
        new Product("나이키 후드티", "의류", 70000, "검정", 8)
    );

    @Tool(description = "카테고리, 최대 가격, 색상 조건으로 상품 목록을 검색합니다.")
    public List<Product> searchProducts(
            @ToolParam(description = "상품 카테고리") String category,
            @ToolParam(description = "최대 가격, 원 단위") int maxPrice,
            @ToolParam(description = "상품 색상") String color
    ) {
        if (maxPrice < 0) {
            throw new IllegalArgumentException("가격은 0 이상이어야 합니다.");
        }

        return products.stream()
                .filter(p -> p.category().equals(category))
                .filter(p -> p.price() <= maxPrice)
                .filter(p -> p.color().equals(color))
                .toList();
    }

    @Tool(description = "정확한 상품명으로 상품 상세정보를 조회합니다.")
    public Product getProductDetail(
            @ToolParam(description = "조회할 정확한 상품명") String name
    ) {
        return products.stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    @Tool(description = "정확한 상품명으로 현재 재고 수량을 확인합니다.")
    public ToolResult<Integer> checkStock(
            @ToolParam(description = "재고를 확인할 정확한 상품명")
            String name
    ) {
        if (name == null || name.isBlank()) {
            return ToolResult.fail("상품명이 비어 있습니다.");
        }

        return products.stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .map(p -> ToolResult.ok(p.stock()))
                .orElseGet(() ->
                        ToolResult.fail("존재하지 않는 상품입니다.")
                );
    }
}