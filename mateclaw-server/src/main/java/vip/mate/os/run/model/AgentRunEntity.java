package vip.mate.os.run.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mate_agent_run")
public class AgentRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    @TableField(value = "conversation_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long conversationId;

    @TableField(value = "trace_id", updateStrategy = FieldStrategy.ALWAYS)
    private String traceId;

    private String runType;

    @TableField(value = "source_type", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceType;

    @TableField(value = "source_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceRef;

    private String status;

    @TableField(value = "current_step_key", updateStrategy = FieldStrategy.ALWAYS)
    private String currentStepKey;

    @TableField(value = "checkpoint_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String checkpointRef;

    @TableField(value = "input_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String inputRef;

    @TableField(value = "output_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String outputRef;

    @TableField(value = "failure_code", updateStrategy = FieldStrategy.ALWAYS)
    private String failureCode;

    @TableField(value = "failure_message", updateStrategy = FieldStrategy.ALWAYS)
    private String failureMessage;

    @TableField(value = "created_by", updateStrategy = FieldStrategy.ALWAYS)
    private Long createdBy;

    @TableField(value = "started_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime startedAt;

    @TableField(value = "completed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
