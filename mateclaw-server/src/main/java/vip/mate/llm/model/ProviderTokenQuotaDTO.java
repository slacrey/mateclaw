package vip.mate.llm.model;

public record ProviderTokenQuotaDTO(
        long limitTokens,
        long usedTokens,
        long remainingTokens,
        boolean exhausted) {

    public static ProviderTokenQuotaDTO from(ProviderTokenQuotaEntity quota) {
        if (quota == null) {
            return null;
        }
        long limit = quota.getLimitTokens() != null ? quota.getLimitTokens() : 0L;
        long used = quota.getUsedTokens() != null ? quota.getUsedTokens() : 0L;
        long remaining = Math.max(0L, limit - used);
        return new ProviderTokenQuotaDTO(limit, used, remaining, remaining <= 0L);
    }
}
