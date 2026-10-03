package org.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.example.rag.config.ClarificationProperties;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 *
 * clarify 是动词，意思是“澄清、阐明”；‌
 * \clarification 是它的名词形式，意思是“澄清、说明”或“澄清的结果”。简单来说，前者表示“去做澄清这个动作”，后者表示“澄清这件事本身或它的结果
 * 澄清判定服务
 * <p>
 * <b>职责边界</b>：
 * - 只负责"判断是否模糊"和"生成反问文本"
 * - 不负责"什么时候反问"——那是 ChatService 的决策
 * - 不做检索——检索结果由调用方传入
 * <p>
 * <b>为什么独立成 Service</b>：
 * 判定逻辑会随业务演进（加更多信号、调阈值、换 NLP 判断），
 * 独立出来便于单测和迭代。
 */
@Slf4j
@Service
public class ClarificationService {

    /**
     * 知识库意图关键词——query 含任一才算"疑似知识库问题"
     * <p>
     * <b>为什么用关键词而非 LLM 分类</b>：
     * 澄清判定必须是"零成本前置"——调 LLM 分类就本末倒置了。
     * 关键词规则覆盖 90% 场景，剩余交给"结果分数低但不澄清"兜底。
     */
    private static final List<String> KB_INTENT_KEYWORDS = List.of(
            "是什么", "什么是", "怎么", "如何", "为什么",
            "哪些", "多少", "有没有", "能否", "是否",
            "规定", "规范", "制度", "标准", "条款", "办法",
            "要求", "流程", "步骤", "区别", "条件"
    );

    /**
     * 明确的"非知识库"信号——含任一直接跳过澄清
     * <p>
     * 这些是"工具调用 / 自我介绍 / 闲聊"的高置信信号。
     */
    private static final List<String> NON_KB_SIGNALS = List.of(
            "我在学", "我关注", "我对", "感兴趣",
            "我叫", "我是", "记住", "以后", "提醒我",
            "讲个", "说说", "来点", "帮我算", "计算"
    );

    private final ClarificationProperties props;

    public ClarificationService(ClarificationProperties props) {
        this.props = props;
    }

    // ==================== 判定：是否模糊 ====================

    /**
     * 判断检索结果是否"不明确"
     * <p>
     * <b>判定边界（重要）</b>：
     * "无结果"≠"模糊"。无结果有两种含义：
     * <ol>
     *   <li>问的是知识库，但库里没有 → 直接答"没有相关资料"，不反问</li>
     *   <li>问的根本不是知识库问题（如"我叫小明"）→ 走正常流程（工具/闲聊）</li>
     * </ol>
     * 两种情况都不该触发澄清——只有"有结果但不确定"才反问。
     * <p>
     * <b>为什么不判空</b>：
     * 空列表意味着检索"没找到"——这在语义上不是"模糊"，
     * 是"确定没有"。反问"你是不是想问别的"是错误引导。
     */
    public boolean isAmbiguous(String query, List<Document> docs) {
        if (!props.isEnabled()) {
            return false;
        }

        // ① 无结果 → 不澄清（已有逻辑）
        if (docs == null || docs.isEmpty()) {
            log.info("[澄清判定] 无检索结果 → 不澄清");
            return false;
        }

        // ★ ② D53 新增：非知识库信号 → 直接跳过
        if (query != null) {
            for (String signal : NON_KB_SIGNALS) {
                if (query.contains(signal)) {
                    log.info("[澄清判定] 检测到非知识库信号 [{}] → 跳过澄清", signal);
                    return false;
                }
            }

            // ★ ③ D53 新增：不含任何知识库疑问词 → 跳过
            boolean hasKbIntent = KB_INTENT_KEYWORDS.stream()
                    .anyMatch(query::contains);
            if (!hasKbIntent) {
                log.info("[澄清判定] 无知识库意图关键词 → 跳过澄清");
                return false;
            }
        }

        // ④ 后续逻辑不变（分低才澄清）
        List<Double> scores = docs.stream()
                .map(d -> {
                    Object s = d.getMetadata().get("rerank_score");
                    return s instanceof Number n ? n.doubleValue() : null;
                })
                .filter(Objects::nonNull)
                .toList();

        if (scores.isEmpty()) {
            log.warn("[澄清判定] 无 rerank_score 字段，跳过判定");
            return false;
        }

        double maxScore = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        long qualifiedCount = scores.stream()
                .filter(s -> s >= props.getMinTopScore())
                .count();

        log.info("[澄清判定] 最高分={}, 高分文档数={}, 阈值=({}, {})",
                String.format("%.3f", maxScore), qualifiedCount,
                props.getMinTopScore(), props.getMinQualifiedDocs());

        if (maxScore < props.getMinTopScore()) {
            log.info("[澄清判定] 最高分低于阈值 → 模糊");
            return true;
        }
        if (qualifiedCount < props.getMinQualifiedDocs()) {
            log.info("[澄清判定] 高分文档数不足 → 模糊");
            return true;
        }
        return false;
    }

    // ==================== 生成：反问文本 ====================

    /**
     * 构造反问文本
     * <p>
     * <b>结构</b>：
     * <pre>
     * [CLARIFY]抱歉，您的问题「XXX」我暂时没找到足够相关的资料。
     *
     * 不过我看到这些可能相关的文档：
     * 1. 《家庭医生签约协议书》
     * 2. 《2024报销制度》
     *
     * 能否补充一下您的具体场景？比如部门、年份、具体条款？
     * </pre>
     *
     * <b>为什么用前缀</b>：
     * 前端靠这个前缀识别"这是反问"——特殊渲染成黄色气泡、加"请澄清"标签。
     */
    public String buildClarification(String query, List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        sb.append(props.getPrefix());

        if (docs == null || docs.isEmpty()) {
            sb.append("抱歉，我没有找到与「").append(query).append("」相关的资料。\n\n");
            sb.append("请确认：\n");
            sb.append("1. 是否换一种说法描述问题？\n");
            sb.append("2. 或者补充更多的上下文信息？");
            return sb.toString();
        }

        sb.append("抱歉，「").append(query).append("」这个问题我不太确定您具体想问什么。\n\n");

        // 列出检索到的文档来源——给用户提示
        Set<String> sources = docs.stream()
                .map(d -> (String) d.getMetadata().get("source"))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (!sources.isEmpty()) {
            sb.append("我找到了这些可能相关的资料：\n");
            int i = 1;
            for (String src : sources) {
                if (i > 3) break;    // 最多列 3 个
                sb.append(i++).append(". 《").append(src).append("》\n");
            }
            sb.append("\n");
        }

        sb.append("能否补充一下您的具体需求？比如：\n");
        sb.append("- 所属部门（财务部 / 研发部 / ……）\n");
        sb.append("- 时间范围（哪一年的版本）\n");
        sb.append("- 具体场景或关键词");

        return sb.toString();
    }
}