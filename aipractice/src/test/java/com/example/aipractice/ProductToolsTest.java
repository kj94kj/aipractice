package com.example.aipractice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProductToolsTest {

    private final ProductTools productTools = new ProductTools();

    @Test
    void 조건에_맞는_상품만_반환한다() {
        var result = productTools.searchProducts(
                "운동화", 100000, "검정"
        );

        assertEquals(2, result.size());

        assertTrue(result.stream()
                .allMatch(p -> p.price() <= 100000));

        assertTrue(result.stream()
                .allMatch(p -> p.color().equals("검정")));
    }

    @Test
    void 검색결과가_없으면_빈목록을_반환한다() {
        var result = productTools.searchProducts(
                "운동화", 1000, "검정"
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void 음수_가격이면_예외가_발생한다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> productTools.searchProducts(
                        "운동화", -1000, "검정"
                )
        );
    }
    
    @Test
    void 정상적인_상품_재고조회() {

        var result = productTools.checkStock("나이키 운동화");

        assertTrue(result.success());
        assertEquals(12, result.data());
        assertNull(result.error());
    }

    @Test
    void 존재하지_않는_상품_조회() {

        var result = productTools.checkStock("없는 상품");

        assertFalse(result.success());
        assertNull(result.data());
        assertEquals("존재하지 않는 상품입니다.", result.error());
    }

    @Test
    void 상품명이_비어있는_경우() {

        var result = productTools.checkStock("");

        assertFalse(result.success());
        assertEquals("상품명이 비어 있습니다.", result.error());
    }

    @Test
    void 재고가_0개인_상품() {

        var result = productTools.checkStock("뉴발란스 운동화");

        assertTrue(result.success());
        assertEquals(0, result.data());
    }
    
    /*
    @Test
    void 시스템_장애_발생_테스트() {

        assertThrows(
            ProductServiceException.class,
            () -> productTools.checkProductSystem()
        );
    }
    */
}