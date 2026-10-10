package org.example.multiagent.exception;

import org.example.multiagent.model.AgentRole;

/**
 * 多 Agent 异常（D71）
 *
 * <h3>何时用本异常 / 何时用 AgentResponse.fail</h3>
 * <p>
 * <b>用 AgentResponse.fail</b>：
 * <ul>
 *   <li>LLM 调用超时——业务上"这次没成功"，但系统正常</li>
 *   <li>Worker 回答质量低——需要主管决策"是否重试"</li>
 *   <li>消息在 RabbitMQ 中丢失——可观测的失败</li>
 * </ul>
 * <p>
 * <b>用 AgentException</b>：
 * <ul>
 *   <li>role 为 null——编程错误</li>
 *   <li>注册的角色找不到对应的 ChatClient——装配错误</li>
 *   <li>TaskGraph 自相矛盾（依赖环）——数据错误</li>
 * </ul>
 *
 * <h3>为什么携带 role</h3>
 * <p>
 * 多 Agent 场景下异常定位最重要的是"哪个 Agent 出错了"——
 * 日志里有了 role 可以直接过滤。parentRole 用于追踪"主管调 Worker 失败"的链路。
 */
public class AgentException extends RuntimeException {

    public enum Code {
        /** 目标角色未注册——装配错误 */
        AGENT_NOT_FOUND,
        /** 反思流程异常——通常是 LLM 返回格式不符 */
        REFLECTION_FAILED,
        /** 调用超时 */
        TIMEOUT,
        /** 消息总线异常（D74 用） */
        BUS_ERROR,
        /** 任务拆解失败（D72 用） */
        DECOMPOSITION_FAILED,
        /** 冲突无法解决（D76 用） */
        CONFLICT_UNRESOLVED
    }

    private final Code code;
    private final AgentRole role;

    public AgentException(Code code, AgentRole role, String message) {
        super(message);
        this.code = code;
        this.role = role;
    }

    public AgentException(Code code, AgentRole role, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.role = role;
    }

    public Code getCode() {
        return code;
    }

    public AgentRole getRole() {
        return role;
    }

    /**
     * 快捷构造——无 role 时用
     */
    public static AgentException of(Code code, String message) {
        return new AgentException(code, null, message);
    }

    public static AgentException of(Code code, AgentRole role, String message) {
        return new AgentException(code, role, message);
    }

    public static AgentException of(Code code, AgentRole role, String message, Throwable cause) {
        return new AgentException(code, role, message, cause);
    }
}