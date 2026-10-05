/**
 * FirstAgentProject/
 *         ├── agent-core/src/main/java/org/example/workflow/
 *         │   ├── model/
 *         │   │   ├── WorkflowRequest.java          # 入口请求
 * │   │   ├── WorkflowResult.java           # 最终结果
 * │   │   ├── WorkflowContext.java          # 上下文
 * │   │   ├── EmailDraft.java               # 邮件草稿
 * │   │   └── StepTrace.java                # 步骤轨迹
 * │   ├── step/
 *         │   │   ├── RetrieveStep.java             # ① 检索
 * │   │   ├── SummarizeStep.java            # ② 总结
 * │   │   ├── ComposeEmailStep.java         # ③ 生成邮件
 * │   │   └── SendEmailStep.java            # ④ 发送邮件
 * │   ├── tool/
 *         │   │   └── EmailTools.java               # 邮件发送工具（模拟）
 *         │   ├── exception/
 *         │   │   └── WorkflowException.java        # 工作流异常
 * │   └── SequentialEmailWorkflow.java      # 主流程编排
 * │
 *         └── agent-api/src/main/java/org/example/controller/
 *         └── WorkflowController.java           # REST 入口
 */
package org.example.workflow;