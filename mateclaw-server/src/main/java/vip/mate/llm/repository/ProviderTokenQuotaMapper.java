package vip.mate.llm.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.mate.llm.model.ProviderTokenQuotaEntity;

@Mapper
public interface ProviderTokenQuotaMapper extends BaseMapper<ProviderTokenQuotaEntity> {

    @Update("""
            UPDATE mate_provider_token_quota
               SET used_tokens = used_tokens + #{delta},
                   update_time = CURRENT_TIMESTAMP
             WHERE workspace_id = #{workspaceId}
               AND provider_id = #{providerId}
            """)
    int incrementUsedTokens(@Param("workspaceId") Long workspaceId,
                            @Param("providerId") String providerId,
                            @Param("delta") long delta);
}
