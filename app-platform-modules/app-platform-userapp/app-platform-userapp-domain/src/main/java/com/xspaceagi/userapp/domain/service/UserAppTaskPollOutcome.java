package com.xspaceagi.userapp.domain.service;

/**
 * pollOnce 的轮询结论。
 */
public class UserAppTaskPollOutcome {

    // finished=true 表示轮询可以终止
    private final boolean finished;

    // buildArtifactReady=true 表示 BUILD 任务下游已 completed 且未超时
    private final boolean buildArtifactReady;

    private UserAppTaskPollOutcome(boolean finished, boolean buildArtifactReady) {
        this.finished = finished;
        this.buildArtifactReady = buildArtifactReady;
    }

    /** 轮询终止 */
    public static UserAppTaskPollOutcome finished() {
        return new UserAppTaskPollOutcome(true, false);
    }

    /** 继续下一轮 */
    public static UserAppTaskPollOutcome continuePolling() {
        return new UserAppTaskPollOutcome(false, false);
    }

    /** BUILD 任务下游已完成，等待应用层产物归档 */
    public static UserAppTaskPollOutcome buildArtifactReady() {
        return new UserAppTaskPollOutcome(true, true);
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isBuildArtifactReady() {
        return buildArtifactReady;
    }
}
