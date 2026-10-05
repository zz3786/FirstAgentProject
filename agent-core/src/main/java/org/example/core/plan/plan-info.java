/**
 * agent-core/src/main/java/org/example/plan/
 * ├── config/
 * │   ├── PlanProperties.java              # yml 配置
 * │   └── PlanChatClientConfig.java        # 三个 ChatClient + 工具包装
 * ├── model/
 * │   ├── Plan.java                        # 完整计划
 * │   ├── PlanStep.java                    # 单步定义
 * │   ├── StepResult.java                  # 单步结果
 * │   ├── PlanExecutionState.java          # 执行状态（可序列化）
 * │   ├── PlanRequest.java                 # 入口请求
 * │   ├── PlanResult.java                  # 最终结果
 * │   └── PlanStatus.java                  # 状态枚举
 * ├── exception/
 * │   └── PlanException.java               # 统一异常
 * ├── planner/
 * │   ├── PlannerService.java              # 生成计划
 * │   ├── PlanValidator.java               # 计划校验
 * │   └── ReplannerService.java            # 失败重规划
 * ├── executor/
 * │   ├── StepExecutor.java                # 单步执行（超时+重试）
 * │   └── PlanExecutor.java                # 整体循环调度
 * ├── store/
 * │   └── PlanStateStore.java              # Redis 状态持久化
 * ├── PlanAndExecuteService.java           # 门面
 * └── PlanSynthesizer.java                 # 结果汇总
 *
 * agent-api/src/main/java/org/example/controller/
 * └── PlanController.java                  # REST 入口
 */

package org.example.core.plan;