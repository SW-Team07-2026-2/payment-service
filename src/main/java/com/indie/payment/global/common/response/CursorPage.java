package com.indie.payment.global.common.response;

import com.indie.payment.global.common.util.Cursor;

import java.util.List;
import java.util.function.Function;

/** 커서 페이지 응답 — { items, nextCursor, hasNext } */
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasNext) {

    /** size + 1 건을 조회해 넘긴 결과로 다음 페이지 유무를 판단합니다 */
    public static <T> CursorPage<T> of(List<T> fetched, int size, Function<T, Cursor> cursorOf) {
        boolean hasNext = fetched.size() > size;
        List<T> items = hasNext ? fetched.subList(0, size) : fetched;
        String next = hasNext ? cursorOf.apply(items.get(items.size() - 1)).encode() : null;
        return new CursorPage<>(items, next, hasNext);
    }
}
