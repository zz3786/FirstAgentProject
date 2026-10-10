package org.example.multiagent.worker;

import org.example.multiagent.model.AgentRequest;
import org.example.multiagent.model.AgentResponse;
import org.example.multiagent.model.AgentRole;

/**
 * Worker Agent 接口（D72）
 *
 * <h3>职责边界</h3>
 * <p>
 * Worker 是"领域专家"——它只回答本领域的问题，不做任务拆解、不做聚合。
 * <ul>
 *   <li>财务 Worker：订单、金额、成本、税务</li>
 *   <li>技术 Worker：架构、代码、性能、选型</li>
 *   <li>法务 Worker：合同、合规、隐私、知识产权</li>
 * </ul>
 *
 * <h3>为什么不叫 DomainAgent / ExpertAgent</h3>
 * <p>
 * "Worker" 是 Supervisor 模式的标准术语（业界普遍使用）。
 * 命名一致便于团队沟通——"Worker" 比 "DomainExpert" 更直观。
 *
 * <h3>实现者约定</h3>
 * <ul>
 *   <li>必须实现 {@link #role()}——返回本 Worker 负责的角色</li>
 *   <li>必须实现 {@link #execute(AgentRequest)}——执行单个子任务</li>
 *   <li>内部应封装自身的 System Prompt 和工具集</li>
 *   <li>不应抛异常——失败通过 {@code AgentResponse.fail} 返回</li>
 * </ul>
 *
 * <h3>D73 的实现方向</h3>
 * <p>
 * D73 会提供：
 * <ul>
 *   <li>{@code AbstractWorkerAgent}——模板基类（封装超时、日志、异常兜底）</li>
 *   <li>{@code FinancialWorkerAgent} / {@code TechnicalWorkerAgent} / {@code LegalWorkerAgent}</li>
 *   <li>每个 Worker 有独立的 {@code ChatClient}（带专属 System Prompt + ToolProfile）</li>
 * </ul>
 * <p>
 * 本接口在 D73 <b>不会改</b>——只是实现类增多。
 */
public interface WorkerAgent {

    /**
     * 本 Worker 负责的角色。
     * <p>
     * {@code WorkerAgentRegistry} 按这个值索引——
     * 注册时如果发现重复 role 会抛异常（防止两个 Worker 抢同一个角色）。
     */
    AgentRole role();

    /**
     * 执行单个子任务
     *
     * @param request 子任务请求（input 是子任务描述）
     * @return 执行结果；失败时 success=false
     */
    AgentResponse execute(AgentRequest request);
}