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
@TableName("mate_browser_session")
public class BrowserSessionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(value = "workspace_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long workspaceId;

    @TableField(value = "run_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long runId;

    private String edgeSessionId;

    private String browserKind;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String subject;

    private String status;

    @TableField(value = "agent_version", updateStrategy = FieldStrategy.ALWAYS)
    private String agentVersion;

    @TableField(value = "last_heartbeat_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastHeartbeatAt;

    @TableField(value = "metadata_json", updateStrategy = FieldStrategy.ALWAYS)
    private String metadataJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
