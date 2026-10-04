package org.example.rag.retrieval.tools;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.retrieval.model.RetrievalProfile;
import org.example.rag.retrieval.service.RetrievalProfileService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索偏好工具
 * <p>
 * <b>职责</b>：把"用户显式表达的检索偏好"转化为 RetrievalProfile 并保存。
 * <p>
 * <b>触发场景</b>：
 * <ul>
 *   <li>用户说"以后问报销问题优先看财务部的"</li>
 *   <li>用户说"别给我推荐合同类的了，我只关心制度"</li>
 *   <li>用户说"回答太啰嗦了，多给我看精确条款"（→ 提高关键词权重）</li>
 * </ul>
 * <p>
 * <b>为什么放在 agent-rag 而不是 agent-memory</b>：
 * RetrievalProfileService 在 agent-rag——工具依赖它，必须同模块。
 * agent-core 依赖 agent-rag，所以工具能被 ChatService 注册。
 */
@Slf4j
@Component
public class RetrievalPreferenceTools {

    private final RetrievalProfileService profileService;

    public RetrievalPreferenceTools(RetrievalProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 保存用户的检索偏好
     * <p>
     * <b>为什么参数分开设计</b>：
     * 模型难以一次性给出完整的 RetrievalProfile（5 个字段），
     * 拆成"维度 + 值"让模型可以分多次调用逐步完善。
     */
    @Tool(description = "记住用户的检索偏好。当用户说'以后优先给我看XX部门的文档'、" +
            "'我只关心XX类型的文件'、'我更喜欢精确条款'时调用。" +
            "不要主动调用——只在用户明确表达偏好时调用。")
    public String saveRetrievalPreference(
            @ToolParam(description = "用户ID") String userId,
            @ToolParam(description = "偏好维度：department / doc_type / style") String dimension,
            @ToolParam(description = "偏好值。department 时填部门名（如'财务部'）；" +
                    "doc_type 时填类型（如'制度'）；style 时填 exact（精确）或 semantic（语义）")
            String value) {

        if (userId == null || userId.isBlank() || value == null || value.isBlank()) {
            return "❌ 参数不完整，无法保存";
        }

        try {
            // ① 读取现有画像——在已有基础上追加
            RetrievalProfile current = profileService.get(userId);

            // ② 按维度追加
            RetrievalProfile updated = switch (dimension.toLowerCase()) {
                case "department" -> appendDepartment(current, value);
                case "doc_type" -> appendDocType(current, value);
                case "style" -> adjustStyle(current, value);
                default -> null;
            };

            if (updated == null) {
                return "❌ 不支持的偏好维度: " + dimension;
            }

            // ③ 保存——标记来源为显式
            profileService.save(userId, updated, "explicit");
            return "✅ 已记住您的偏好：" + dimension + " = " + value;

        } catch (Exception e) {
            log.error("保存检索偏好失败: userId={}, dimension={}", userId, dimension, e);
            return "⚠️ 保存失败，请稍后重试";
        }
    }

    // ==================== 维度追加逻辑 ====================

    private RetrievalProfile appendDepartment(RetrievalProfile current, String dept) {
        List<String> depts = new ArrayList<>(current.preferredDepartments());
        if (!depts.contains(dept)) {
            depts.add(dept);
        }
        return new RetrievalProfile(
                depts, current.preferredDocTypes(),
                current.weightBiasVec(), current.weightBiasKw(), current.topKBias()
        );
    }

    private RetrievalProfile appendDocType(RetrievalProfile current, String type) {
        List<String> types = new ArrayList<>(current.preferredDocTypes());
        if (!types.contains(type)) {
            types.add(type);
        }
        return new RetrievalProfile(
                current.preferredDepartments(), types,
                current.weightBiasVec(), current.weightBiasKw(), current.topKBias()
        );
    }

    /**
     * 调整风格偏好
     * <p>
     * exact → 关键词权重 +0.1
     * semantic → 向量权重 +0.1
     */
    private RetrievalProfile adjustStyle(RetrievalProfile current, String style) {
        double biasVec = current.weightBiasVec();
        double biasKw = current.weightBiasKw();

        if ("exact".equalsIgnoreCase(style)) {
            biasKw = 0.10;
            biasVec = -0.10;
        } else if ("semantic".equalsIgnoreCase(style)) {
            biasVec = 0.10;
            biasKw = -0.10;
        }

        return new RetrievalProfile(
                current.preferredDepartments(), current.preferredDocTypes(),
                biasVec, biasKw, current.topKBias()
        );
    }
}