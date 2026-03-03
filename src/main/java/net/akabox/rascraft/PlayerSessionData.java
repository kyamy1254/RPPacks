package net.akabox.rascraft;

import org.bukkit.entity.LivingEntity;

/**
 * プレイヤーのセッションデータを統合管理するクラス
 * メモリ効率を向上させるため、複数のHashMapを1つのオブジェクトにまとめる
 */
public class PlayerSessionData {
    private LivingEntity lastAttackedTarget;
    private int displayTimer;
    private String lastSentMessage;
    private long lastSentTime;

    public PlayerSessionData() {
        this.lastAttackedTarget = null;
        this.displayTimer = 0;
        this.lastSentMessage = null;
        this.lastSentTime = 0L;
    }

    public LivingEntity getLastAttackedTarget() {
        return lastAttackedTarget;
    }

    public void setLastAttackedTarget(LivingEntity target) {
        this.lastAttackedTarget = target;
    }

    public int getDisplayTimer() {
        return displayTimer;
    }

    public void setDisplayTimer(int ticks) {
        this.displayTimer = ticks;
    }

    public String getLastSentMessage() {
        return lastSentMessage;
    }

    public void setLastSentMessage(String message) {
        this.lastSentMessage = message;
    }

    public long getLastSentTime() {
        return lastSentTime;
    }

    public void setLastSentTime(long time) {
        this.lastSentTime = time;
    }

    public void reset() {
        this.lastAttackedTarget = null;
        this.displayTimer = 0;
        this.lastSentMessage = null;
        this.lastSentTime = 0L;
    }
}
