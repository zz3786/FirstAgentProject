package org.example.api.common.handler;

import lombok.extern.slf4j.Slf4j;
import org.example.api.common.ApiResponse;
import org.example.core.workflow.core.exception.WorkflowNodeException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
@Order(13)
public class WorkflowNodeExceptionHandler {

    @ExceptionHandler(WorkflowNodeException.class)
    public ResponseEntity<ApiResponse<Void>> handle(WorkflowNodeException e) {
        log.error("工作流节点异常: node={}, msg={}", e.getNodeName(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "工作流节点[" + e.getNodeName() + "]执行失败"));
    }
}
