package net.kdt.pojavlaunch.runtime;

/** 저장 계정의 표시 정보다. 토큰이나 인증 유효성을 포함하지 않는다. */
public final class AccountSummary {
    /** 저장 항목의 불투명 키다. Minecraft UUID와 다르며 호스트가 경로로 해석하지 않는다. */
    public final String id;
    public final String username;
    public final String profileId;
    public final boolean microsoft;
    public final boolean demo;
    public final boolean selected;

    AccountSummary(
            String id,
            String username,
            String profileId,
            boolean microsoft,
            boolean selected
    ) {
        this.id = id;
        this.username = username;
        this.profileId = profileId;
        this.microsoft = microsoft;
        this.demo = username.startsWith("Demo.");
        this.selected = selected;
    }
}
