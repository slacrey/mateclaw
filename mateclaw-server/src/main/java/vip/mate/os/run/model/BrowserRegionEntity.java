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
@TableName("mate_browser_region")
public class BrowserRegionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(value = "run_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long runId;

    @TableField(value = "tab_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long tabId;

    private String regionKey;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String label;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Double x;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Double y;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Double width;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Double height;

    @TableField(value = "safe_x", updateStrategy = FieldStrategy.ALWAYS)
    private Double safeX;

    @TableField(value = "safe_y", updateStrategy = FieldStrategy.ALWAYS)
    private Double safeY;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Double confidence;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String source;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String fingerprint;

    @TableField(value = "metadata_json", updateStrategy = FieldStrategy.ALWAYS)
    private String metadataJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
