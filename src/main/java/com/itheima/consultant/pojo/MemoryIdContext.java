package com.itheima.consultant.pojo;

public class MemoryIdContext {
    private static final ThreadLocal<String> CURRENT_MEMORY_ID = new ThreadLocal<>();

    public static void set(String id) {
        CURRENT_MEMORY_ID.set(id);
    }

    public static String get() {
        return CURRENT_MEMORY_ID.get();
    }

    public static void clear() {
        CURRENT_MEMORY_ID.remove();
    }
}
