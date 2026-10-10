package org.example.multiagent.model;

/**
 * Agent 消息类型（D74）
 *
 * <h3>三种类型</h3>
 * <ul>
 *   <li>{@code TASK}——任务请求：主管 → Worker</li>
 *   <li>{@code RESULT}——任务结果：Worker → 主管</li>
 *   <li>{@code ERROR}——执行失败：Worker → 主管（携带错误信息）</li>
 * </ul>
 *
 * <h3>为什么 ERROR 独立成类型而不是 RESULT 的一种</h3>
 * <p>
 * 消息接收方需要根据类型快速路由：
 * <ul>
 *   <li>{@code RESULT} → 完成 Future</li>
 *   <li>{@code ERROR} → 异常完成 Future（或以失败响应完成）</li>
 * </ul>
 * 分开后，消费逻辑的 switch 更清晰——不用在 RESULT 里判"error 字段是否为空"。
 *
 * <h3>为什么不用一个字段 error != null 表达</h3>
 * <p>
 * "类型"和"状态"是两个维度：
 * <ul>
 *   <li>类型：这是什么消息？（TASK / RESULT / ERROR）</li>
 *   <li>状态：任务成功与否？（success / fail）</li>
 * </ul>
 * 混在一起会让 MQ 监控变复杂——运维无法直接按"ERROR 消息数"告警。
 */
public enum MessageType {

    /** 任务请求——主管发往 Worker */
    TASK,

    /** 任务结果——Worker 成功执行后回执 */
    RESULT,

    /** 执行失败——Worker 执行异常时回执 */
    ERROR
}