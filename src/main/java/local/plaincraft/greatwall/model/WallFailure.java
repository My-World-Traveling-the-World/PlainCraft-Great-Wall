/* 文件职责：万里长城统一业务异常，携带 FailureCode。 */
package local.plaincraft.greatwall.model;

/**
 * 统一业务异常。code 必须非空，message 描述可读原因，cause 保留原始异常。
 */
public final class WallFailure extends RuntimeException {

    private final WallTypes.FailureCode code;

    public WallFailure(WallTypes.FailureCode code, String message) {
        super(message);
        if (code == null) throw new IllegalArgumentException("code required");
        if (message == null) throw new IllegalArgumentException("message required");
        this.code = code;
    }

    public WallFailure(WallTypes.FailureCode code, String message, Throwable cause) {
        super(message, cause);
        if (code == null) throw new IllegalArgumentException("code required");
        if (message == null) throw new IllegalArgumentException("message required");
        this.code = code;
    }

    public WallTypes.FailureCode code() {
        return code;
    }
}
