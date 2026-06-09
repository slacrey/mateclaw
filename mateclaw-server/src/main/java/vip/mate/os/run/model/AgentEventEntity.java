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
@TableName("mate_agent_event")
public class AgentEventEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    @TableField(value = "step_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long stepId;

    private String eventType;

    private String severity;

    @TableField(value = "payload_json", updateStrategy = FieldStrategy.ALWAYS)
    private String payloadJson;

    @TableField(value = "artifact_ids", updateStrategy = FieldStrategy.ALWAYS)
    private String artifactIds;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
