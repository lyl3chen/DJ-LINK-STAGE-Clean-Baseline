package dbclient.sync;

/**
 * 轻量统一时钟：将离散参考时间连续化。
 * 不依赖外部线程，调用 nowSeconds() 时按最近参考点推算。
 */
public class TimecodeClock {
    private volatile double referenceTimeSec = 0.0;
    private volatile long referenceNanoTime = System.nanoTime();
    private volatile boolean playing = false;
    private volatile double speed = 1.0;

    private volatile double lastNowSec = 0.0;
    private volatile double driftSec = 0.0;
    private volatile boolean hardRelock = false;

    // 小于约2帧(25fps)时做软校正
    private static final double SOFT_THRESHOLD_SEC = 0.08;

    public synchronized void ingestReference(double refSec, boolean isPlaying, double spd) {
        long now = System.nanoTime();
        double safeSpeed = Double.isFinite(spd) ? spd : 1.0;
        if (safeSpeed < 0.0) safeSpeed = 0.0;
        if (safeSpeed > 2.0) safeSpeed = 2.0;

        double predicted = nowSecondsInternal(now);
        driftSec = refSec - predicted;
        hardRelock = Math.abs(driftSec) > SOFT_THRESHOLD_SEC;

        if (hardRelock) {
            // 硬重定位：明显偏差直接贴齐
            referenceTimeSec = refSec;
            referenceNanoTime = now;
        } else {
            // 软校正：小偏差平滑并入，不猛跳
            referenceTimeSec = predicted + driftSec * 0.25;
            referenceNanoTime = now;
        }

        playing = isPlaying;
        speed = safeSpeed;
        lastNowSec = nowSecondsInternal(now);
    }

    public synchronized double nowSeconds() {
        long now = System.nanoTime();
        lastNowSec = nowSecondsInternal(now);
        return lastNowSec;
    }

    private double nowSecondsInternal(long nowNs) {
        if (!playing) return Math.max(0.0, referenceTimeSec);
        double dt = Math.max(0.0, (nowNs - referenceNanoTime) / 1_000_000_000.0);
        return Math.max(0.0, referenceTimeSec + dt * speed);
    }

    public synchronized String debugSnapshot() {
        return "TimecodeClock{now=" + lastNowSec + ", ref=" + referenceTimeSec +
                ", drift=" + driftSec + ", playing=" + playing +
                ", speed=" + speed + ", hardRelock=" + hardRelock + "}";
    }
}
