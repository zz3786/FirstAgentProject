package org.example.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class TodoTools {

    // 内存存储待办列表
    private static final List<Todo> TODOS = new ArrayList<>();
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // 内部类：待办事项结构
    public static class Todo {
        public String title;
        public String priority;  // HIGH, MEDIUM, LOW
        public String dueDate;   // yyyy-MM-dd HH:mm

        @Override
        public String toString() {
            return String.format("[%s] %s (截止: %s)", priority, title, dueDate);
        }
    }

    @Tool(description = "创建一个新的待办事项。支持设置标题、优先级(HIGH/MEDIUM/LOW)和截止时间。")
    public String createTodo(
            @ToolParam(description = "待办事项标题", required = true) String title,
            @ToolParam(description = "优先级：HIGH、MEDIUM、LOW，默认 MEDIUM", required = false)
            String priority,
            @ToolParam(description = "截止时间，格式 yyyy-MM-dd HH:mm，例如 2026-09-20 18:00", required = false)
            String dueDate) {

        Todo todo = new Todo();
        todo.title = title;
        todo.priority = (priority == null || priority.isEmpty()) ? "MEDIUM" : priority.toUpperCase();
        todo.dueDate = (dueDate == null || dueDate.isEmpty())
                ? LocalDateTime.now().plusDays(1).format(FORMATTER)
                : dueDate;

        TODOS.add(todo);
        return "✅ 已创建待办：" + todo;
    }

    @Tool(description = "列出当前所有待办事项")
    public String listTodos() {
        if (TODOS.isEmpty()) {
            return "当前没有待办事项 🎉";
        }
        StringBuilder sb = new StringBuilder("📋 当前待办列表：\n");
        for (int i = 0; i < TODOS.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(TODOS.get(i)).append("\n");
        }
        return sb.toString();
    }

}
