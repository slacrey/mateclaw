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
@TableName("mate_agent_artifact")
public class AgentArtifactEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(value = "run_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long runId;

    @TableField(value = "step_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long stepId;

    private Long workspaceId;

    private String artifactUri;

    private String artifactKind;

    @TableField(value = "content_type", updateStrategy = FieldStrategy.ALWAYS)
    private String contentType;

    private String storageKind;

    @TableField(value = "storage_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String storageRef;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sha256;

    @TableField(value = "size_bytes", updateStrategy = FieldStrategy.ALWAYS)
    private Long sizeBytes;

    @TableField(value = "metadata_json", updateStrategy = FieldStrategy.ALWAYS)
    private String metadataJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
